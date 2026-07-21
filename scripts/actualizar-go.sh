#!/usr/bin/env bash
# Reconstruye e instala Katapult Go de punta a punta:
#   CI (GitHub Actions) → descargar IPA → firmar en local → instalar por USB.
#
# Se usa cuando cambia la HERRAMIENTA (contrato, catálogo, Swift, versiones).
# Para desarrollar tu app NO hace falta: la lógica recarga en caliente.
#
# Requiere: gh con sesión, zsign configurado en katapult.json, y haber hecho
# push antes (CI compila lo que está en GitHub, no tu árbol local).
# La contraseña del certificado se puede pasar en KATAPULT_CERT_PASSWORD.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! git diff --quiet origin/main..HEAD 2>/dev/null || [ -n "$(git status --porcelain)" ]; then
    echo "⚠  Hay cambios sin pushear: CI va a compilar lo que esté en GitHub."
    read -r -p "   ¿Continuar igualmente? [s/N] " r
    [[ "$r" == "s" || "$r" == "S" ]] || exit 1
fi

echo "→ Disparando build en GitHub Actions…"
gh workflow run katapult-go.yml
sleep 8
RUN=$(gh run list --workflow=katapult-go.yml --limit 1 --json databaseId --jq '.[0].databaseId')

echo "→ Run $RUN — esperando al runner de macOS (10-15 min la primera vez)…"
gh run watch "$RUN" --exit-status

echo "→ Descargando IPA…"
rm -f katapult-go/dist/*.ipa
gh run download "$RUN" -n katapult-go-unsigned -D katapult-go/dist

echo "→ Firmando…"
./gradlew :cli:installDist -q
./cli/build/install/katapult/bin/katapult sign --ipa katapult-go/dist/KatapultGo-unsigned.ipa

if idevice_id -l 2>/dev/null | grep -q .; then
    echo "→ Instalando en el iPhone…"
    ./cli/build/install/katapult/bin/katapult install --ipa katapult-go/dist/KatapultGo-firmada.ipa
    echo "✓ Listo: abre Katapult Go en el iPhone."
else
    echo "⚠  iPhone no conectado. Cuando lo conectes:"
    echo "   ./cli/build/install/katapult/bin/katapult install --ipa katapult-go/dist/KatapultGo-firmada.ipa"
fi
