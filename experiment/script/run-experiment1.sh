#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

read -r -a PARTITION_SIZES <<< "${EXPERIMENT_PARTITION_SIZES:-2 3 10 25 100 500 1000 2500 5000 10000}"
REPETITIONS=5
SEED="experiment/seed/aqi.csv"
RESULTS="experiment/results"

mvn -q -DskipTests package
JAVA_HOME_FROM_MAVEN="$(mvn -version | sed -n -e 's/^Java home: //p' -e 's/^Java version: .*runtime: //p' | head -n 1)"
JAVA="$JAVA_HOME_FROM_MAVEN/bin/java"

mkdir -p "$RESULTS"
RUN_DIR="$(mktemp -d "$RESULTS/run.XXXXXX")"
LOG="$RUN_DIR/experiment.log"
CSV="$RUN_DIR/aqi.csv"
SQL="$RUN_DIR/experiment.sql"
trap 'rm -f "$CSV"' EXIT

# Keep pollutant labels as strings, replacing their list commas so COPY can read
# them without quoted-field support. Drop unit, which also contains quoted commas.
python3 - "$SEED" "$CSV" <<'PY'
import csv
import sys

source_path, output_path = sys.argv[1:]
with open(source_path, newline="", encoding="ascii") as source:
    reader = csv.reader(source)
    header = next(reader)
    pollutant = header.index("prominent_pollutants")
    retained = [i for i, name in enumerate(header) if name != "unit"]

    with open(output_path, "w", encoding="ascii") as output:
        for row in reader:
            row[pollutant] = row[pollutant].replace(",", ";")
            fields = [row[i] for i in retained]
            if any("," in field or '"' in field for field in fields):
                raise SystemExit("Unexpected quoted comma in a retained CSV column")
            output.write(",".join(fields) + "\n")
PY

# Build one script per partition-size run: load the same data, warm up once,
# then run five SELECTs whose durations will be used in the summary.
CSV_PATH="${CSV#"$ROOT/"}"
sed "s|__AQI_CSV_PATH__|$CSV_PATH|g" experiment/script/experiment1.sql > "$SQL"
for ((i = 0; i <= REPETITIONS; i++)); do
    echo "SELECT * FROM aqi WHERE aqi_value > 200;" >> "$SQL"
done

for size in "${PARTITION_SIZES[@]}"; do
    "$JAVA" \
        "-Dlog4j.configurationFile=$ROOT/experiment/script/log4j2-experiment.xml" \
        "-Dexperiment.logFile=$ROOT/$LOG" \
        "-Ddatasys.dataDirectory=$ROOT/$RUN_DIR/data-$size" \
        "-Ddatasys.maxRowsPerPartition=$size" \
        -jar target/engine.jar -f "$ROOT/$SQL" > /dev/null
done

# Read this run's SELECT timings and exclude the first (warm-up) duration.
python3 - "$LOG" "${PARTITION_SIZES[@]}" <<'PY'
import csv
import re
import statistics
import sys

log_path, *sizes = sys.argv[1:]
durations = {size: [] for size in sizes}
pattern = re.compile(r"durationMs=(\d+\.\d{3})")

with open(log_path, newline="", encoding="utf-8") as log:
    for fields in csv.reader(log):
        if len(fields) < 8 or fields[5] != "StatementExecutor":
            continue
        size = fields[6].removeprefix("partitionSize=")
        match = pattern.search(fields[7])
        if size in durations and match:
            durations[size].append(float(match.group(1)))

with open(log_path.rsplit("/", 1)[0] + "/summary.csv", "w", newline="") as output:
    writer = csv.writer(output)
    writer.writerow(["maxRowsPerPartition", "warmupMs", "medianMs", "meanMs"])
    for size in sizes:
        runs = durations[size]
        if len(runs) != 6:
            raise SystemExit(f"Expected 6 timings for partition size {size}; found {len(runs)}")
        measured = runs[1:]
        writer.writerow([
            size,
            f"{runs[0]:.3f}",
            f"{statistics.median(measured):.3f}",
            f"{statistics.mean(measured):.3f}",
        ])
PY

echo "Experiment log: $LOG"
echo "Summary: $RUN_DIR/summary.csv"
