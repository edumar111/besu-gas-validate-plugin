#!/usr/bin/env bash
set -euo pipefail

# Directorio donde vive este script (raíz del nodo local).
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Binario Besu con soporte Falcon (genesis usa falcon512block).
BESU_BIN="${BESU_BIN:-/Users/edumar111/tools/besu-25.8.0-falcon/bin/besu}"

# Besu 25.8 requiere Java 21+. Permite override con JAVA_HOME.
# Resolución por orden de preferencia:
#   1. JAVA_HOME ya seteado y que efectivamente sea JDK 21 (si no, se ignora)
#   2. Homebrew keg-only openjdk@21
#   3. /usr/libexec/java_home -v 21 (sólo si el symlink global está creado;
#      ojo: macOS retorna la versión más alta disponible aunque no sea 21,
#      por eso validamos la versión devuelta)

# Si el JAVA_HOME heredado del shell no es JDK 21, lo descartamos para que
# el resto del script pueda elegir uno apropiado.
if [ -n "${JAVA_HOME:-}" ]; then
  if ! "${JAVA_HOME}/bin/java" -version 2>&1 | head -1 | grep -qE '"21\.'; then
    unset JAVA_HOME
  fi
fi

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  if BREW_JDK21="$(brew --prefix openjdk@21 2>/dev/null)" && [ -d "${BREW_JDK21}/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="${BREW_JDK21}/libexec/openjdk.jdk/Contents/Home"
  fi
fi

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  if CANDIDATE="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
    if "${CANDIDATE}/bin/java" -version 2>&1 | head -1 | grep -qE '"21\.'; then
      export JAVA_HOME="${CANDIDATE}"
    fi
  fi
fi

if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

# Validación temprana de versión de Java.
JAVA_MAJOR="$(java -version 2>&1 | awk -F\" '/version/ {print $2}' | awk -F. '{print $1}')"
if [ -z "${JAVA_MAJOR}" ] || [ "${JAVA_MAJOR}" -lt 21 ]; then
  echo "ERROR: Besu 25.8 requiere Java 21+. Detectado: $(java -version 2>&1 | head -1)" >&2
  echo "       Instalá un JDK 21 (ej: 'brew install openjdk@21') y exportá JAVA_HOME." >&2
  exit 1
fi

# Rutas locales bajo node/.
DATA_PATH="${SCRIPT_DIR}/data"
LOGS_DIR="${SCRIPT_DIR}/logs"
PLUGINS_DIR="${SCRIPT_DIR}/data/plugins"
GENESIS_FILE="${SCRIPT_DIR}/genesis.json"
CONFIG_FILE="${SCRIPT_DIR}/config.toml"
LOG4J_FILE="${SCRIPT_DIR}/log.xml"

mkdir -p "${DATA_PATH}" "${LOGS_DIR}" "${PLUGINS_DIR}"

# El log4j usa la propiedad de sistema besu.logs.dir para ubicar los archivos.
# besu.plugins.dir apunta al directorio donde dejamos el JAR del plugin (por
# default Besu busca en ${besu.home}/plugins/, que es el dir de instalación).
# ratelimit.membershipContract es la addr del MembershipRegistry deployado;
# el plugin la lee al arrancar. Si redeployás el registry, actualizá este valor.
MEMBERSHIP_CONTRACT="${MEMBERSHIP_CONTRACT:-0x948B3c65b89DF0B4894ABE91E6D02FE579834F8F}"
export LOG4J_CONFIGURATION_FILE="${LOG4J_FILE}"
export JAVA_OPTS="${JAVA_OPTS:-} -Dbesu.logs.dir=${LOGS_DIR} -Dbesu.plugins.dir=${PLUGINS_DIR} -Dratelimit.membershipContract=${MEMBERSHIP_CONTRACT}"

# chainId / network-id viene de genesis.json (650540).
exec "${BESU_BIN}" \
  --data-path="${DATA_PATH}" \
  --genesis-file="${GENESIS_FILE}" \
  --network-id=650540 \
  --config-file="${CONFIG_FILE}" \
  --logging=INFO
