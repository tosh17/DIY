"""LoRA Qwen2.5-1.5B. Лосс только на JSON ответа."""

import json
import os
import time
from pathlib import Path

import torch
from peft import LoraConfig, TaskType, get_peft_model
from torch.utils.data import Dataset
from transformers import AutoModelForCausalLM, AutoTokenizer, Trainer, TrainerCallback, TrainingArguments

MODEL_ID = os.environ.get("MODEL_ID", "Qwen/Qwen2.5-1.5B-Instruct")
MAX_LEN = int(os.environ.get("MAX_LEN", "2048"))
SEED = 42
ROOT = Path(__file__).resolve().parent
OUT = ROOT / "output"
OUT.mkdir(parents=True, exist_ok=True)
tokenizer = None


class LossLog(TrainerCallback):
    def on_log(self, args, state, control, logs=None, **kwargs):
        if not logs:
            return
        row = {"step": state.global_step}
        for key, value in logs.items():
            if isinstance(value, (int, float)):
                row[key] = value
        with (OUT / "loss.jsonl").open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(row) + "\n")


class TokenDataset(Dataset):
    def __init__(self, rows):
        self.rows = rows

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, index):
        return self.rows[index]


def read_jsonl(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def encode(row):
    messages = row["messages"]
    prompt_ids = tokenizer.apply_chat_template(messages[:-1], tokenize=True, add_generation_prompt=True)
    full_ids = tokenizer.apply_chat_template(messages, tokenize=True, add_generation_prompt=False)
    if full_ids[: len(prompt_ids)] != prompt_ids:
        return None
    if len(full_ids) > MAX_LEN:
        return None
    labels = [-100] * len(prompt_ids) + full_ids[len(prompt_ids) :]
    if not any(token != -100 for token in labels):
        return None
    return {"input_ids": full_ids, "labels": labels, "attention_mask": [1] * len(full_ids)}


def collate(features):
    width = max(len(item["input_ids"]) for item in features)
    pad_id = tokenizer.pad_token_id
    input_ids, labels, attention = [], [], []
    for item in features:
        pad = width - len(item["input_ids"])
        input_ids.append(item["input_ids"] + [pad_id] * pad)
        labels.append(item["labels"] + [-100] * pad)
        attention.append(item["attention_mask"] + [0] * pad)
    return {
        "input_ids": torch.tensor(input_ids, dtype=torch.long),
        "labels": torch.tensor(labels, dtype=torch.long),
        "attention_mask": torch.tensor(attention, dtype=torch.long),
    }


def main():
    global tokenizer
    torch.set_num_threads(int(os.environ.get("OMP_NUM_THREADS", "3")))
    started = time.time()
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token

    train_raw = read_jsonl(ROOT / "dataset" / "train.jsonl")
    valid_raw = read_jsonl(ROOT / "dataset" / "valid.jsonl")
    test_users = {row["user"] for row in read_jsonl(ROOT / "dataset" / "test.jsonl")}
    train_users = {row["user"] for row in train_raw}
    valid_users = {row["user"] for row in valid_raw}
    if train_users & valid_users or train_users & test_users or valid_users & test_users:
        raise SystemExit("вопрос из valid или test попал в другой сплит")

    train_rows = [item for row in train_raw if (item := encode(row))]
    valid_rows = [item for row in valid_raw if (item := encode(row))]
    if len(train_rows) != len(train_raw) or len(valid_rows) != len(valid_raw):
        raise SystemExit(
            f"часть примеров не влезла: train {len(train_rows)}/{len(train_raw)} valid {len(valid_rows)}/{len(valid_raw)}"
        )
    print(f"split train={len(train_rows)} valid={len(valid_rows)} test={len(test_users)}", flush=True)

    model = AutoModelForCausalLM.from_pretrained(MODEL_ID, torch_dtype=torch.float32, low_cpu_mem_usage=True)
    model.config.use_cache = False
    model.gradient_checkpointing_enable()
    model.enable_input_require_grads()
    model = get_peft_model(
        model,
        LoraConfig(
            r=8,
            lora_alpha=16,
            lora_dropout=0.05,
            bias="none",
            task_type=TaskType.CAUSAL_LM,
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj"],
        ),
    )
    model.print_trainable_parameters()
    loss_path = OUT / "loss.jsonl"
    if loss_path.exists():
        loss_path.unlink()
    epochs = int(os.environ.get("EPOCHS", "3"))
    trainer = Trainer(
        model=model,
        args=TrainingArguments(
            output_dir=str(OUT / "trainer"),
            per_device_train_batch_size=1,
            per_device_eval_batch_size=1,
            gradient_accumulation_steps=4,
            learning_rate=2e-4,
            num_train_epochs=epochs,
            warmup_ratio=0.05,
            weight_decay=0.01,
            lr_scheduler_type="cosine",
            logging_steps=5,
            eval_strategy="epoch",
            save_strategy="no",
            report_to="none",
            use_cpu=True,
            fp16=False,
            bf16=False,
            dataloader_pin_memory=False,
            remove_unused_columns=False,
            seed=SEED,
            data_seed=SEED,
            optim="adamw_torch",
            max_grad_norm=1.0,
        ),
        train_dataset=TokenDataset(train_rows),
        eval_dataset=TokenDataset(valid_rows),
        data_collator=collate,
        callbacks=[LossLog()],
    )
    print("train start", flush=True)
    train_started = time.time()
    result = trainer.train()
    adapter = OUT / "adapter"
    trainer.save_model(str(adapter))
    tokenizer.save_pretrained(str(adapter))
    meta = {
        "model_id": MODEL_ID,
        "method": "LoRA",
        "train_examples": len(train_rows),
        "valid_examples": len(valid_rows),
        "epochs": epochs,
        "train_seconds": round(time.time() - train_started, 1),
        "wall_seconds": round(time.time() - started, 1),
        "train_loss": result.training_loss,
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(started)),
        "finished_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    (OUT / "run_meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("meta " + json.dumps(meta, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
