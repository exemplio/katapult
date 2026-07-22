import UIKit

/// Fase 2 del espejo nativo: un UITextField TRANSPARENTE superpuesto sobre el
/// video, exactamente donde Compose tiene su campo enfocado. El texto visible
/// es el del stream (lo pinta Compose); este campo aporta lo que el video no
/// puede: el teclado nativo de iOS, el caret y la edición local. Cada cambio
/// viaja por WebSocket al servidor, que lo inyecta en el TextField de Compose.
///
/// Limitación permanente: el autocompletado de dominio (contraseñas de iCloud,
/// códigos SMS…) no aparece, porque el campo no pertenece a una app con
/// entitlements del dominio real.
final class SuperposicionTexto: UITextField, UITextFieldDelegate {

    /// Canal de salida hacia el servidor (JSON por WebSocket).
    var alMensaje: ((String) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        isHidden = true
        borderStyle = .none
        backgroundColor = .clear
        textColor = .clear          // el texto real lo pinta Compose en el video
        tintColor = .systemBlue     // el caret sí es nuestro
        autocorrectionType = .no
        autocapitalizationType = .none
        smartQuotesType = .no
        smartDashesType = .no
        spellCheckingType = .no
        addTarget(self, action: #selector(cambio), for: .editingChanged)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) no se usa") }

    /// Coloca y activa el campo según el mensaje "foco" del servidor.
    /// [marco] ya viene proyectado a coordenadas de la vista.
    func mostrar(valor: String, teclado: String, seguro: Bool, accion: String, marco: CGRect) {
        frame = marco.insetBy(dx: -2, dy: -2)
        isSecureTextEntry = seguro
        keyboardType = tipoTeclado(teclado)
        returnKeyType = tipoRetorno(accion)
        // Mientras el usuario edita, ESTE campo es la verdad: ignorar el eco
        // del servidor evita que un mensaje rezagado borre lo recién tecleado.
        if !isFirstResponder {
            text = valor
        }
        isHidden = false
        if !isFirstResponder {
            becomeFirstResponder()
        }
    }

    /// El servidor mandó "blur": Compose perdió el foco.
    func ocultar() {
        guard !isHidden else { return }
        isHidden = true
        resignFirstResponder()
        text = ""
    }

    @objc private func cambio() {
        // JSONSerialization escapa el texto (comillas, emojis…); nada de armar
        // el JSON a mano con interpolación.
        let dict: [String: Any] = ["type": "texto", "valor": text ?? ""]
        if let data = try? JSONSerialization.data(withJSONObject: dict),
           let json = String(data: data, encoding: .utf8) {
            alMensaje?(json)
        }
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        alMensaje?(#"{"type":"ime"}"#)
        // No hacemos resign aquí: si la acción era Next, Compose moverá el foco
        // y llegará otro "foco"; si era Done, llegará "blur". El servidor manda.
        return false
    }

    private func tipoTeclado(_ t: String) -> UIKeyboardType {
        switch t {
        case "email": return .emailAddress
        case "numero": return .numberPad
        case "decimal": return .decimalPad
        case "telefono": return .phonePad
        case "uri": return .URL
        default: return .default
        }
    }

    private func tipoRetorno(_ a: String) -> UIReturnKeyType {
        switch a {
        case "next": return .next
        case "go": return .go
        case "search": return .search
        case "send": return .send
        case "done": return .done
        default: return .default
        }
    }
}
