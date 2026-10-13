#!/system/bin/sh
# Arranque automático do cachalote — instalar em /data/adb/service.d/cachalote.sh
# O Magisk corre os scripts de service.d no arranque, como root, em late_start.
BASE="${CACHALOTE_BASE:-/data/adb/cachalote}"

# Dar tempo ao sistema (rede, /data montado, zygote) antes de subir o motor.
sleep 20

if [ -x "$BASE/bin/cachalote" ]; then
  "$BASE/bin/cachalote" start >>"$BASE/logs/boot.log" 2>&1
fi
