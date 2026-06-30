#!/bin/bash
# Graphon Installer v2.1 - Clean Installation & Daemon Cleanup

INSTALL_DIR="$HOME/.graphon"
BIN_DIR="$HOME/.local/bin"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Installing Graphon to $INSTALL_DIR..."

# 0. Stop existing daemon if running (to prevent zombie socket conflicts)
if [ -f "$INSTALL_DIR/daemon.pid" ]; then
    PID=$(cat "$INSTALL_DIR/daemon.pid")
    if kill -0 "$PID" 2>/dev/null; then
        echo "Stopping existing daemon (PID: $PID)..."
        kill "$PID" 2>/dev/null
        sleep 1
    fi
    # Clean up old socket and pid files
    rm -f "$INSTALL_DIR/daemon.pid" "$INSTALL_DIR/daemon.sock"
fi

# 1. Buat struktur folder
mkdir -p "$INSTALL_DIR/bin"
mkdir -p "$INSTALL_DIR/lib"
mkdir -p "$INSTALL_DIR/jre"
mkdir -p "$INSTALL_DIR/plugins"
mkdir -p "$BIN_DIR"

# 2. Copy file inti (Overwrite existing)
cp "$SCRIPT_DIR/graphon.groovy" "$INSTALL_DIR/lib/graphon.groovy"
cp "$SCRIPT_DIR/graphon" "$INSTALL_DIR/bin/graphon"

# 3. Pastikan executable
chmod +x "$INSTALL_DIR/bin/graphon"

# 4. Buat symlink ke ~/.local/bin (yang sudah ada di PATH Termux/Linux)
ln -sf "$INSTALL_DIR/bin/graphon" "$BIN_DIR/graphon"

echo ""
echo "✅ Graphon installed successfully!"
echo "   Engine:   $INSTALL_DIR/lib/graphon.groovy"
echo "   Wrapper:  $INSTALL_DIR/bin/graphon"
echo "   Symlink:  $BIN_DIR/graphon"
echo ""
echo "You can now use 'graphon' from anywhere."
