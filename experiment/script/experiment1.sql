-- Expanded by run-experiment1.sh with a temporary headerless CSV path.
-- The runner appends one warm-up SELECT and the configured measured SELECTs.
CREATE TABLE aqi (
    date STRING,
    state STRING,
    area STRING,
    number_of_monitoring_stations LONG,
    prominent_pollutants STRING,
    aqi_value LONG,
    air_quality_status STRING,
    note STRING
);
COPY aqi FROM '__AQI_CSV_PATH__';