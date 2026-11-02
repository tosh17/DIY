#!/bin/bash
set -euo pipefail
cd /home/troot/diy
echo "wait train $(date -u +%Y-%m-%dT%H:%M:%SZ)"
code=$(docker wait diy-train)
echo "train exit $code"
if [ "$code" != "0" ]; then
  echo FAIL
  exit 1
fi
mkdir -p /home/troot/diy/llama.cpp
if [ ! -x /home/troot/diy/llama.cpp/llama-quantize ]; then
  rm -rf /tmp/llama-b12032
  tar -xzf /tmp/llama-bin.tar.gz -C /tmp
  cp -a /tmp/llama-b12032/. /home/troot/diy/llama.cpp/
  chmod +x /home/troot/diy/llama.cpp/llama-quantize
fi
if [ ! -f /home/troot/diy/llama.cpp/convert_hf_to_gguf.py ]; then
  curl -fsSL -o /home/troot/diy/llama.cpp/convert_hf_to_gguf.py \
    https://raw.githubusercontent.com/ggml-org/llama.cpp/b12032/convert_hf_to_gguf.py
fi
docker run --rm --memory=8g --cpus=3 \
  -v /home/troot/diy:/lab/diy \
  -v /home/troot/lora-lab-src/hf-cache:/lab/hf-cache \
  -e HF_HOME=/lab/hf-cache \
  -e HF_HUB_OFFLINE=1 \
  -e TRANSFORMERS_OFFLINE=1 \
  -e MODEL_ID=Qwen/Qwen2.5-1.5B-Instruct \
  -e LLAMA_CPP=/lab/diy/llama.cpp \
  -e LD_LIBRARY_PATH=/lab/diy/llama.cpp \
  -w /lab/diy/train \
  lora-lab:latest bash -lc "pip install -q gguf && python -u merge_quantize.py"
docker cp /home/troot/diy/train/output/diy-q4.gguf ollama:/tmp/diy-q4.gguf
docker exec ollama sh -c 'printf "FROM /tmp/diy-q4.gguf\nPARAMETER temperature 0\nPARAMETER num_ctx 2048\n" > /tmp/Modelfile && ollama create diy-parts:q4 -f /tmp/Modelfile'
echo DONE
