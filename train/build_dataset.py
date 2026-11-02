"""Вопросы по каталогу. Скрытые детали и контрольные формулировки в обучение не входят."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "src" / "main" / "resources"
OUT = Path(__file__).resolve().parent / "dataset"

TRAIN_IDEAS = [
    ("Нужно включить мотор на 12 В с пина Arduino 5 В.", ["IRLZ44N", "1N4007"]),
    ("Собери ключ для моторчика 12 вольт от пятивальтового пина.", ["IRLZ44N", "1N4007"]),
    ("Как погасить выброс обмотки мотора?", ["1N4007"]),
    ("Хочу мигалку на светодиоде без микроконтроллера.", ["NE555", "LED 5 мм", "Резистор 220 Ом"]),
    ("Светодиод на пин 5 В, чтобы не сгорел.", ["LED 5 мм", "Резистор 220 Ом"]),
    ("Из аккумулятора 12 В нужны стабильные 5 В.", ["LM7805"]),
    ("Плата с Wi-Fi для поделки.", ["ESP32"]),
    ("Какая микросхема стоит сердцем Arduino Uno?", ["ATmega328P"]),
    ("Измерить температуру и влажность в комнате.", ["DHT22"]),
    ("Датчик температуры на длинном проводе.", ["DS18B20", "Резистор 4.7 кОм"]),
    ("Измерить расстояние до препятствия.", ["HC-SR04"]),
    ("Маленький чёрно-белый экран по I2C.", ["SSD1306", "Резистор 4.7 кОм"]),
    ("Усилить слабый сигнал с датчика.", ["LM358"]),
    ("NPN-ключ на небольшое реле.", ["2N2222"]),
    ("Слабый NPN для сигнального тока.", ["BC547"]),
    ("Плюсовой ключ на PNP.", ["BC557"]),
    ("Парный PNP к 2N3904.", ["2N3906"]),
    ("Подтянуть кнопку к плюсу.", ["Резистор 10 кОм"]),
    ("Подтяжка линии I2C.", ["Резистор 4.7 кОм"]),
    ("Быстрый сигнальный диод.", ["1N4148"]),
    ("Силовой MOSFET, затвор буду открывать десяткой вольт.", ["IRF540N"]),
    ("Обычный NPN на 200 миллиампер.", ["2N3904"]),
]

VALID_IDEAS = [
    ("Ключ нижнего плеча для мотора 12 В и диод параллельно обмотке.", ["IRLZ44N", "1N4007"]),
    ("Питание 5 В из 12 В для цифровой части.", ["LM7805"]),
]

TEST_SEEN = [
    ("От Uno надо крутить мотор 12 В.", ["IRLZ44N", "1N4007"]),
    ("Батарея 12 В, логике нужны ровно 5 В.", ["LM7805"]),
    ("Метеостанция: и тепло, и влажность.", ["DHT22"]),
    ("Экран 128 на 64 по шине I2C.", ["SSD1306", "Резистор 4.7 кОм"]),
    ("Просто помигать лампочкой-светодиодом от таймера.", ["NE555", "LED 5 мм", "Резистор 220 Ом"]),
]

TEST_HELD = [
    ("Нужен стабилизатор, напряжение выставлю сам, от 1,25 В.", ["LM317"]),
    ("Дарлингтон, чтобы тянуть около 5 ампер.", ["TIP120"]),
    ("Диод Шоттки на три ампера.", ["SS34"]),
    ("Крошечный MOSFET в корпусе TO-92.", ["BS170"]),
]


def gold(parts, by_name):
    picks = [{"name": name, "why": by_name[name]["summary"]} for name in parts]
    reply = "Возьми " + "; ".join(f"{name}: {by_name[name]['summary']}" for name in parts) + "."
    return json.dumps({"reply": reply, "picks": picks}, ensure_ascii=False)


def main():
    catalog = json.loads((RES / "catalog.json").read_text(encoding="utf-8"))
    by_name = {part["name"]: part for part in catalog}
    template = (RES / "prompt.txt").read_text(encoding="utf-8")
    index = "\n".join(f"- {part['name']} — {part['summary']}" for part in catalog)
    system = template.replace("{{catalog}}", index)
    train, valid, test = [], [], []

    def add(bucket, user, names, split):
        for name in names:
            if name not in by_name:
                raise SystemExit(f"нет детали {name}")
        bucket.append(
            {
                "split": split,
                "user": user,
                "names": names,
                "messages": [
                    {"role": "system", "content": system},
                    {"role": "user", "content": user},
                    {"role": "assistant", "content": gold(names, by_name)},
                ],
            }
        )

    for part in catalog:
        if part["held"]:
            continue
        add(train, f"Что это за деталь {part['name']}?", [part["name"]], "train")
        add(train, f"Для поделки беру {part['name']}. На какое напряжение и ток он рассчитан?", [part["name"]], "train")
        add(valid, f"Паспорт детали {part['name']}.", [part["name"]], "valid")
    for user, names in TRAIN_IDEAS:
        add(train, user, names, "train")
    for user, names in VALID_IDEAS:
        add(valid, user, names, "valid")
    for user, names in TEST_SEEN:
        add(test, user, names, "test")
    for user, names in TEST_HELD:
        add(test, user, names, "test")
        add(test, f"Расскажи про {names[0]}.", names, "test")

    users = {"train": set(), "valid": set(), "test": set()}
    for split, rows in (("train", train), ("valid", valid), ("test", test)):
        for row in rows:
            if row["user"] in users[split]:
                raise SystemExit(f"повтор в {split}: {row['user']}")
            users[split].add(row["user"])
    if users["train"] & users["valid"] or users["train"] & users["test"] or users["valid"] & users["test"]:
        raise SystemExit("формулировка попала в два сплита")
    held = {part["name"] for part in catalog if part["held"]}
    for row in train + valid:
        for name in held:
            if name in row["user"]:
                raise SystemExit(f"скрытая деталь в обучении: {name}")

    OUT.mkdir(parents=True, exist_ok=True)

    def write(name, rows):
        for index, row in enumerate(rows, start=1):
            row["id"] = f"{name}-{index:03d}"
        text = "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n"
        (OUT / f"{name}.jsonl").write_text(text, encoding="utf-8")

    write("train", train)
    write("valid", valid)
    write("test", test)
    stats = {"train": len(train), "valid": len(valid), "test": len(test), "held": sorted(held)}
    (OUT / "stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(stats, ensure_ascii=False))


if __name__ == "__main__":
    main()
