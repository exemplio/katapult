import AVFoundation
import CoreMedia
import VideoToolbox

/// Decodificador H.264 por hardware (VideoToolbox).
///
/// Recibe frames del servidor como [tipo: UInt8] + NAL units **Annex B**.
/// VideoToolbox NO come Annex B, y falla en NEGRO sin dar error si se intenta.
/// Hay que hacer dos conversiones, ambas aquí:
///  1. Extraer SPS/PPS del stream (ffmpeg los mete en cada keyframe) y crear
///     el CMVideoFormatDescription con CreateFromH264ParameterSets — crearlo
///     "a pelo" con ancho×alto no configura el códec.
///  2. Reempaquetar cada NAL en AVCC: longitud big-endian de 4 bytes en vez
///     de start codes 00 00 01.
/// La sesión se crea PEREZOSA al ver el primer keyframe, que es donde llegan
/// los parámetros; si el stream los cambia, se recrea.
///
/// No es actor: el callback C de VideoToolbox no soporta isolation de actor.
/// Todo se llama desde el hilo principal.
final class DecodificadorH264 {

    private var session: VTDecompressionSession?
    private var formatoDesc: CMVideoFormatDescription?
    var callback: ((CMSampleBuffer) -> Void)?
    private var sps: Data?
    private var pps: Data?

    /// Deja el decodificador listo. La sesión real se crea sola al llegar el
    /// primer keyframe: sin los SPS/PPS del stream no hay nada que configurar.
    func iniciar(ancho: Int, alto: Int, fps: Int, onFrame: @escaping (CMSampleBuffer) -> Void) {
        detener()
        callback = onFrame
        print("[DecodificadorH264] esperando SPS/PPS del primer keyframe (\(ancho)×\(alto) @\(fps)fps)")
    }

    /// Un frame del servidor: [tipo: 1=keyframe, 0=delta] + NAL units Annex B.
    func decodificar(datos: Data) {
        guard datos.count > 5 else { return }

        // Annex B → AVCC, cazando SPS/PPS por el camino.
        var avcc = Data()
        for nal in separarNALs(datos.subdata(in: 1..<datos.count)) {
            guard let cabecera = nal.first else { continue }
            switch cabecera & 0x1F {
            case 7: if sps != nal { sps = nal; formatoDesc = nil }    // SPS
            case 8: if pps != nal { pps = nal; formatoDesc = nil }    // PPS
            case 6, 9: break                                          // SEI / AUD: ruido
            default:                                                  // IDR (5), slice (1)…
                var longitud = UInt32(nal.count).bigEndian
                avcc.append(Data(bytes: &longitud, count: 4))
                avcc.append(nal)
            }
        }

        if formatoDesc == nil { crearSesion() }
        guard let session, let formatoDesc, !avcc.isEmpty else { return }

        var blockBuffer: CMBlockBuffer?
        let bbStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: avcc.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: avcc.count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard bbStatus == noErr, let bb = blockBuffer else { return }
        avcc.withUnsafeBytes { (ptr: UnsafeRawBufferPointer) in
            _ = CMBlockBufferReplaceDataBytes(
                with: ptr.baseAddress!,
                blockBuffer: bb,
                offsetIntoDestination: 0,
                dataLength: avcc.count
            )
        }

        var tamanoMuestra = avcc.count
        var sampleBuffer: CMSampleBuffer?
        var timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
            decodeTimeStamp: .invalid
        )
        let sbStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: bb,
            formatDescription: formatoDesc,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &tamanoMuestra,
            sampleBufferOut: &sampleBuffer
        )
        guard sbStatus == noErr, let sb = sampleBuffer else { return }

        // Síncrono y sin procesado temporal: el stream es zerolatency (sin
        // B-frames); pedir reordenación solo retiene frames y suma latencia.
        let status = VTDecompressionSessionDecodeFrame(
            session,
            sampleBuffer: sb,
            flags: [],
            frameRefcon: nil,
            infoFlagsOut: nil
        )
        if status == kVTInvalidSessionErr {
            // La app volvió de background: la sesión de hardware muere y hay
            // que recrearla con los mismos parámetros. self. explícito: el
            // guard de arriba sombreó la propiedad con una copia inmutable.
            self.session = nil
            self.formatoDesc = nil
        }
    }

    /// CMVideoFormatDescription desde los SPS/PPS reales + sesión nueva.
    private func crearSesion() {
        guard let sps, let pps else { return }

        var desc: CMVideoFormatDescription?
        let status: OSStatus = sps.withUnsafeBytes { spsBuf in
            pps.withUnsafeBytes { ppsBuf in
                let punteros: [UnsafePointer<UInt8>] = [
                    spsBuf.bindMemory(to: UInt8.self).baseAddress!,
                    ppsBuf.bindMemory(to: UInt8.self).baseAddress!,
                ]
                let tamanos: [Int] = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: 2,
                    parameterSetPointers: punteros,
                    parameterSetSizes: tamanos,
                    nalUnitHeaderLength: 4, // longitudes AVCC de 4 bytes, como arriba
                    formatDescriptionOut: &desc
                )
            }
        }
        guard status == noErr, let desc else {
            print("[DecodificadorH264] CreateFromH264ParameterSets falló: \(status)")
            return
        }
        formatoDesc = desc

        // Con formato nuevo, la sesión vieja no vale.
        if let vieja = session {
            VTDecompressionSessionInvalidate(vieja)
            session = nil
        }

        // Callback a nivel de archivo (no closure): VideoToolbox exige un
        // puntero a función C sin captura; self viaja como refCon.
        var cb = VTDecompressionOutputCallbackRecord()
        cb.decompressionOutputCallback = decodificadorCallback
        cb.decompressionOutputRefCon = Unmanaged.passUnretained(self).toOpaque()

        let attrs: [NSString: AnyObject] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange as AnyObject,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as AnyObject,
        ]

        var sess: VTDecompressionSession?
        let createStatus = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            formatDescription: desc,
            decoderSpecification: nil,
            imageBufferAttributes: attrs as CFDictionary,
            outputCallback: &cb,
            decompressionSessionOut: &sess
        )
        guard createStatus == noErr, let sess else {
            print("[DecodificadorH264] VTDecompressionSessionCreate falló: \(createStatus)")
            return
        }
        session = sess
        let dims = CMVideoFormatDescriptionGetDimensions(desc)
        print("[DecodificadorH264] sesión creada \(dims.width)×\(dims.height)")
    }

    /// Trocea Annex B en NAL units. Acepta start codes de 3 y 4 bytes (ffmpeg
    /// mezcla ambos: 4 para SPS/keyframes, 3 para el resto). Pariente de la
    /// trampa del servidor: al encontrar el delimitador se salta ENTERO antes
    /// de seguir, o se encuentra a sí mismo.
    private func separarNALs(_ datos: Data) -> [Data] {
        let bytes = [UInt8](datos)
        var nals: [Data] = []
        var inicio = -1
        var i = 0
        while i + 2 < bytes.count {
            if bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 1 {
                if inicio >= 0 {
                    var fin = i
                    // Start code de 4 bytes: el 00 previo es del delimitador.
                    if fin > inicio, bytes[fin - 1] == 0 { fin -= 1 }
                    if fin > inicio { nals.append(Data(bytes[inicio..<fin])) }
                }
                i += 3
                inicio = i
            } else {
                i += 1
            }
        }
        if inicio >= 0, inicio < bytes.count {
            nals.append(Data(bytes[inicio...]))
        }
        return nals
    }

    func detener() {
        if let session {
            VTDecompressionSessionInvalidate(session)
            self.session = nil
        }
        formatoDesc = nil
        callback = nil
        sps = nil
        pps = nil
    }
}

/// Callback C puro para VTDecompressionSession. No captura contexto:
/// recibe el DecodificadorH264 vía decompressionOutputRefCon.
private func decodificadorCallback(
    _ outputRefCon: UnsafeMutableRawPointer?,
    _ sourceFrameRefCon: UnsafeMutableRawPointer?,
    _ status: OSStatus,
    _ infoFlags: VTDecodeInfoFlags,
    _ imageBuffer: CVImageBuffer?,
    _ presentationTimeStamp: CMTime,
    _ presentationDuration: CMTime
) {
    guard status == noErr,
          let refCon = outputRefCon,
          let imageBuffer else { return }

    let decodificador = Unmanaged<DecodificadorH264>.fromOpaque(refCon).takeUnretainedValue()

    // Envolver el CVPixelBuffer en un CMSampleBuffer para AVSampleBufferDisplayLayer.
    var formatDesc: CMVideoFormatDescription?
    CMVideoFormatDescriptionCreateForImageBuffer(
        allocator: kCFAllocatorDefault,
        imageBuffer: imageBuffer,
        formatDescriptionOut: &formatDesc
    )
    guard let fmt = formatDesc else { return }

    var sampleBuffer: CMSampleBuffer?
    var timing = CMSampleTimingInfo(
        duration: presentationDuration,
        presentationTimeStamp: presentationTimeStamp,
        decodeTimeStamp: .invalid
    )
    CMSampleBufferCreateReadyWithImageBuffer(
        allocator: kCFAllocatorDefault,
        imageBuffer: imageBuffer,
        formatDescription: fmt,
        sampleTiming: &timing,
        sampleBufferOut: &sampleBuffer
    )
    guard let sb = sampleBuffer else { return }

    // El layer no tiene timebase configurado, así que los PTS de reloj de
    // host no se proyectan a nada: sin esta marca puede no pintar JAMÁS
    // (tercera causa de pantalla negra, tan silenciosa como las otras dos).
    if let attachments = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: true),
       CFArrayGetCount(attachments) > 0 {
        let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
        CFDictionarySetValue(
            dict,
            Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
            Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
        )
    }

    DispatchQueue.main.async {
        decodificador.callback?(sb)
    }
}
