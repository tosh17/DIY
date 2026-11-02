"""Вливает LoRA в базовую модель и пишет GGUF Q4, если рядом есть llama.cpp."""

import os
import shutil
import subprocess
from pathlib import Path

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

MODEL_ID = os.environ.get("MODEL_ID", "Qwen/Qwen2.5-1.5B-Instruct")
ROOT = Path(__file__).resolve().parent
ADAPTER = ROOT / "output" / "adapter"
MERGED = ROOT / "output" / "merged"
LLAMA = Path(os.environ.get("LLAMA_CPP", "/home/troot/diy/llama.cpp"))


def main():
    tokenizer = AutoTokenizer.from_pretrained(str(ADAPTER))
    base = AutoModelForCausalLM.from_pretrained(MODEL_ID, torch_dtype=torch.float16, low_cpu_mem_usage=True)
    model = PeftModel.from_pretrained(base, str(ADAPTER))
    merged = model.merge_and_unload()
    if MERGED.exists():
        shutil.rmtree(MERGED)
    MERGED.mkdir(parents=True)
    merged.save_pretrained(MERGED, safe_serialization=True)
    tokenizer.save_pretrained(MERGED)
    print(f"merged {MERGED}", flush=True)

    convert = LLAMA / "convert_hf_to_gguf.py"
    quant = LLAMA / "llama-quantize"
    if not convert.exists() or not quant.exists():
        print("llama.cpp ещё не подготовлен, GGUF пропущен", flush=True)
        return
    f16 = ROOT / "output" / "diy-f16.gguf"
    q4 = ROOT / "output" / "diy-q4.gguf"
    subprocess.check_call(["python3", str(convert), str(MERGED), "--outfile", str(f16), "--outtype", "f16"])
    subprocess.check_call([str(quant), str(f16), str(q4), "Q4_K_M"])
    print(f"q4 {q4} bytes {q4.stat().st_size}", flush=True)


if __name__ == "__main__":
    main()
