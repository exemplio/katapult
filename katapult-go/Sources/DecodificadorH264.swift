import AVFoundation
import CoreMedia
import VideoToolbox

/// Decodificador H.264 por hardware (VideoToolbox).
///
/// Recibe datos en el formato del servidor: un byte de tipo (1 = keyframe,
/// 0 = delta) seguido de NAL units Annex B. Las NAL units se pasan a un
/// `VTDecompressionSession` que entrega `CMSampleBuffer` decodificados,
/// listos para mostrar en un `AVSampleBufferDisplayLayer`.
///
/// No es actor: el callback C de VideoToolbox no soporta isolation de actor.
/// Todo se llama desde el hilo principal.
final class DecodificadorH264 {

    private var session: VTDecompressionSession?
    private var formatoDesc: CMVideoFormatDescription?
    var callback: ((CMSampleBuffer) -> Void)?
    private var ancho: Int = 0
    private var alto: Int = 0

    /// Configura el decodificador con las dimensiones del stream.
    /// Debe llamarse UNA sola vez, tras recibir el "hello" del servidor.
    func iniciar(ancho: Int, alto: Int, fps: Int, onFrame: @escaping (CMSampleBuffer) -> Void) {
        detener()
        self.ancho = ancho
        self.alto = alto
        self.callback = onFrame

        let status = CMVideoFormatDescriptionCreate(
            allocator: kCFAllocatorDefault,
            codecType: kCMVideoCodecType_H264,
            width: Int32(ancho),
            height: Int32(alto),
            extensions: nil,
            formatDescriptionOut: &formatoDesc
        )
        guard status == noErr, let desc = formatoDesc else {
            print("[DecodificadorH264] CMVideoFormatDescriptionCreate falló: \(status)")
            return
        }

        // Callback a nivel de archivo (no closure): VideoToolbox exige
        // un puntero a función C, sin captura de contexto. Pasamos self
        // como refCon para resolver la instancia.
        var cb = VTDecompressionOutputCallbackRecord()
        cb.decompressionOutputCallback = decodificadorCallback
        cb.decompressionOutputRefCon = Unmanaged.passUnretained(self).toOpaque()

        let attrs: [NSString: AnyObject] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange as AnyObject,
            kCVPixelBufferWidthKey: ancho as AnyObject,
            kCVPixelBufferHeightKey: alto as AnyObject,
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
        self.session = sess
        print("[DecodificadorH264] iniciado \(ancho)×\(alto) @\(fps)fps")
    }

    /// Recibe un frame tal cual lo envía el servidor: [tipo: UInt8] + [NAL units].
    /// - Tipo 1 = keyframe (IDR), tipo 0 = delta (P/B frame).
    func decodificar(datos: Data) {
        guard let session, datos.count > 1 else { return }

        _ = datos[0]  // tipo: 1=keyframe, 0=delta
        let nalData = datos.subdata(in: 1..<datos.count)

        var blockBuffer: CMBlockBuffer?
        let bbStatus = nalData.withUnsafeBytes { (ptr: UnsafeRawBufferPointer) in
            CMBlockBufferCreateWithMemoryBlock(
                allocator: kCFAllocatorDefault,
                memoryBlock: nil,
                blockLength: nalData.count,
                blockAllocator: kCFAllocatorDefault,
                customBlockSource: nil,
                offsetToData: 0,
                dataLength: nalData.count,
                flags: 0,
                blockBufferOut: &blockBuffer
            )
        }
        guard bbStatus == noErr, let bb = blockBuffer else { return }

        CMBlockBufferReplaceDataBytes(
            with: nalData.withUnsafeBytes { $0.baseAddress! },
            blockBuffer: bb,
            offsetIntoDestination: 0,
            dataLength: nalData.count
        )

        var sampleBuffer: CMSampleBuffer?
        var timing = CMSampleTimingInfo(
            duration: CMTime.invalid,
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
            sampleSizeEntryCount: 0,
            sampleSizeArray: nil,
            sampleBufferOut: &sampleBuffer
        )
        guard sbStatus == noErr, let sb = sampleBuffer else { return }

        let flags: VTDecodeFrameFlags = [
            ._EnableAsynchronousDecompression,
            ._EnableTemporalProcessing,
        ]

        VTDecompressionSessionDecodeFrame(
            session,
            sampleBuffer: sb,
            flags: flags,
            frameRefcon: nil,
            infoFlagsOut: nil
        )
    }

    func detener() {
        if let session {
            VTDecompressionSessionInvalidate(session)
            self.session = nil
        }
        formatoDesc = nil
        callback = nil
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
    if let sb = sampleBuffer {
        DispatchQueue.main.async {
            decodificador.callback?(sb)
        }
    }
}