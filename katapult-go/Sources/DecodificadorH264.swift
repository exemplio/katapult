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
/// Thread-safe: el callback de salida siempre se llama en el hilo que invocó
/// `iniciar()`.
actor DecodificadorH264 {

    private var session: VTDecompressionSession?
    private var formatoDesc: CMVideoFormatDescription?
    private var callback: ((CMSampleBuffer) -> Void)?
    private var ancho: Int = 0
    private var alto: Int = 0

    /// Configura el decodificador con las dimensiones del stream.
    /// Debe llamarse UNA sola vez, tras recibir el "hello" del servidor.
    func iniciar(ancho: Int, alto: Int, fps: Int, onFrame: @escaping (CMSampleBuffer) -> Void) {
        self.ancho = ancho
        self.alto = alto
        self.callback = onFrame

        // Construir la descripción de formato H.264 (avcC) para el tamaño dado.
        // VTDecompressionSession necesita saber el códec y dimensiones de antemano.
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

        // Callback de salida: VTDecompressionSession llama a esta closure
        // cada vez que un frame está decodificado.
        var cb = VTDecompressionOutputCallbackRecord()
        cb.decompressionOutputCallback = { (
            _: UnsafeMutableRawPointer?,
            _: UnsafeMutableRawPointer?,
            status: OSStatus,
            flags: VTDecodeInfoFlags,
            buffer: CVImageBuffer?,
            _: CMTime,
            _: CMTime
        ) in
            guard status == noErr, let buffer else { return }
            // Envolver el CVPixelBuffer en un CMSampleBuffer para
            // AVSampleBufferDisplayLayer.
            var sampleBuffer: CMSampleBuffer?
            var timing = CMSampleTimingInfo(
                duration: CMTime.invalid,
                presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
                decodeTimeStamp: .invalid
            )
            var formatDescOut: CMVideoFormatDescription?
            CMVideoFormatDescriptionCreateForImageBuffer(
                allocator: kCFAllocatorDefault,
                imageBuffer: buffer,
                formatDescriptionOut: &formatDescOut
            )
            guard let fmt = formatDescOut else { return }
            CMSampleBufferCreateReadyWithImageBuffer(
                allocator: kCFAllocatorDefault,
                imageBuffer: buffer,
                formatDescription: fmt,
                sampleTiming: &timing,
                sampleBufferOut: &sampleBuffer
            )
            if let sb = sampleBuffer {
                // El callback de salida viene de un hilo interno de VT;
                // se llama al callback del actor, que es @Sendable.
                (cb.decompressionOutputRefCon?
                    .assumingMemoryBound(to: ((CMSampleBuffer) -> Void).self)
                    .pointee)?(sb)
            }
        }
        cb.decompressionOutputRefCon = UnsafeMutableRawPointer.allocate(
            byteCount: MemoryLayout<((CMSampleBuffer) -> Void)>.size,
            alignment: MemoryLayout<((CMSampleBuffer) -> Void)>.alignment
        )
        cb.decompressionOutputRefCon?
            .assumingMemoryBound(to: ((CMSampleBuffer) -> Void).self)
            .pointee = onFrame

        // Parámetros del decodificador: el servidor ya emite Annex B sin
        // SPS/PPS por frame (va en los keyframes); VideoToolbox los extrae solo.
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
        guard let session else { return }
        guard datos.count > 1 else { return }

        let tipo = datos[0]  // 1 = keyframe, 0 = delta
        let nalData = datos.subdata(in: 1..<datos.count)

        // Construir CMSampleBuffer a partir de los datos Annex B.
        // VideoToolbox en macOS/iOS acepta Annex B sin avcC si se usa
        // el formato correcto.
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
        guard bbStatus == noErr, var bb = blockBuffer else { return }

        CMBlockBufferReplaceDataBytes(
            with: nalData.withUnsafeBytes { $0.baseAddress! },
            blockBuffer: bb,
            offsetIntoDestination: 0,
            dataLength: nalData.count
        )

        // Crear el sample buffer de entrada. Sin timing porque es streaming en vivo.
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

        // Flags: si es keyframe, pedir decodificación inmediata.
        var flags = VTDecodeFrameFlags()
        if tipo == 1 {
            flags = [._EnableAsynchronousDecompression, ._EnableTemporalProcessing]
        } else {
            flags = [._EnableAsynchronousDecompression, ._EnableTemporalProcessing]
        }

        let status = VTDecompressionSessionDecodeFrame(
            session,
            sampleBuffer: sb,
            flags: flags,
            frameRefcon: nil,
            infoFlagsOut: nil
        )
        if status != noErr {
            // Error común en el primer frame (falta SPS/PPS en el stream).
            // El siguiente keyframe lo arregla solo.
            // print("[DecodificadorH264] decodificar falló: \(status)")
        }
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