#!/usr/bin/env bash
set -euo pipefail

MODEL="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
ASSET_ROOT="app/src/main/assets"
DEST="$ASSET_ROOT/$MODEL"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$MODEL.tar.bz2"

required=(
  "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
  "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
  "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
  "tokens.txt"
  "bpe.model"
  "keywords.txt"
)

ready=true
for file in "${required[@]}"; do
  if [[ ! -s "$DEST/$file" ]]; then ready=false; break; fi
done
if [[ "$ready" == true ]]; then
  echo "Magicar KWS assets already ready."
  exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl --fail --location --retry 3 --retry-delay 2 "$URL" -o "$tmp/model.tar.bz2"
tar -xjf "$tmp/model.tar.bz2" -C "$tmp"

mkdir -p "$DEST"
cp "$tmp/$MODEL/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$DEST/"
cp "$tmp/$MODEL/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$DEST/"
cp "$tmp/$MODEL/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$DEST/"
cp "$tmp/$MODEL/tokens.txt" "$DEST/"
cp "$tmp/$MODEL/bpe.model" "$DEST/"

python3 - "$DEST/bpe.model" "$DEST/keywords.txt" <<'PY'
import sys
import sentencepiece as spm
model, output = sys.argv[1], sys.argv[2]
sp = spm.SentencePieceProcessor(model_file=model)
pieces = sp.encode("HEY MAGICAR", out_type=str)
if not pieces:
    raise SystemExit("Could not tokenize HEY MAGICAR")
with open(output, "w", encoding="utf-8") as f:
    f.write(" ".join(pieces) + " @HEY_MAGICAR\n")
print("HEY MAGICAR ->", " ".join(pieces))
PY

for file in "${required[@]}"; do
  test -s "$DEST/$file"
done

du -sh "$DEST"
