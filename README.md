# MicroWatch

MicroWatch is an AI-assisted microplastic screening and water-quality monitoring project, built for the Huawei ICT Competition 2026-2027 Innovation Competition (topic: Creating Innovative AI Applications Powered by Huawei Cloud).

This repository currently contains the **database and sample ID foundation**. The remaining modules are listed under [Project status](#project-status).

## Project status

| Module | Status |
|---|---|
| MySQL database schema | Done |
| Single sample ID generator | Done |
| Three-step sampling records linked by one sample ID | Done (database level) |
| Web API and dashboard | Planned |
| GIS analytics (historical and upstream/downstream data) | Planned |
| Huawei Cloud integration (IoTDA, ModelArts) | Planned |
| MicroFusion-AI multimodal inference | Planned |
| Edge AI optimization and benchmarks | Planned |
| Active learning workflow | Planned |
| Environmental report | Planned |

## What is in this repository

| File | Purpose |
|---|---|
| `schema.sql` | Creates the `microwatch` database and its 7 tables |
| `SampleId.java` | The only place a sample ID is built. Format: `MW-<site>-<yyyymmdd>-<seq>`, for example `MW-S03-20261117-0001` |
| `Demo.java` | Creates one demo sample with its three steps (Collection, Filtration, Imaging) |
| `README.md` | This file |

### Database tables

- `sites`: monitoring locations with coordinates and upstream/downstream position
- `samples`: one row per water sample, keyed by the sample ID
- `sample_steps`: the three sampling steps, all linked to the same sample ID
- `sensor_readings`: water-quality readings, each marked `real` or `simulated`
- `predictions`: AI results with confidence and a review flag
- `expert_reviews`: expert labels for low-confidence predictions
- `model_versions`: model versions with their measured metrics

## Requirements

- MySQL 8.0 or newer (local, or Huawei Cloud RDS for MySQL)
- JDK 17 or newer (`javac -version` must work)
- MySQL Connector/J (the JDBC driver jar), downloaded from https://dev.mysql.com/downloads/connector/j/ (choose "Platform Independent"). Place the `.jar` file in this folder.

## Setup

### 1. Create the database

From this folder:

    mysql -u root -p < schema.sql

If `mysql` is not recognized on Windows, use the full path:

    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p < schema.sql

Check that it worked:

    mysql -u root -p -e "USE microwatch; SHOW TABLES;"

You should see 7 tables. Note that `schema.sql` only works on a fresh database. To reset during development (this deletes all data):

    mysql -u root -p -e "DROP DATABASE microwatch;"

### 2. Compile

    javac -d out SampleId.java Demo.java

### 3. Run the demo

Set your database password as an environment variable (never write it into a file), then run. Replace the jar name with the one you downloaded.

Windows (Command Prompt):

    set DB_PASS=yourpassword
    java -cp "out;mysql-connector-j-26.10.0.jar" Demo

macOS / Linux:

    export DB_PASS=yourpassword
    java -cp "out:mysql-connector-j-26.10.0.jar" Demo

Expected output:

    Created sample: MW-S03-YYYYMMDD-0001

Running it again creates `-0002`, and so on.

### Configuration

| Variable | Default |
|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/microwatch` |
| `DB_USER` | `root` |
| `DB_PASS` | empty |

To use a cloud database, set `DB_URL` to your Huawei Cloud RDS address, for example `jdbc:mysql://<rds-address>:3306/microwatch`, together with `DB_USER` and `DB_PASS`. No code changes are needed.

## Verify the data

    mysql -u root -p -e "USE microwatch; SELECT sample_id, step_no, step_name FROM sample_steps;"

You should see three steps sharing the same sample ID.

## Security notes

- Do not commit passwords, device secrets, or `.env` files.
- Restrict database access to your own IP or a private network.

## Troubleshooting

| Problem | Fix |
|---|---|
| `javac` is not recognized | Install a JDK, not only the Java runtime |
| `No suitable driver found` | The jar name or path after `-cp` is wrong; run `dir *.jar` and copy the exact name |
| `Access denied` | `DB_PASS` or `DB_USER` is wrong |
| `Communications link failure` | The MySQL service is not running |
| `Table already exists` | The schema is already loaded; drop the database to reload it |

## License

Add your chosen open-source license here (for example MIT or Apache-2.0) and include a `LICENSE` file.

## Acknowledgements

Developed for the Huawei ICT Competition 2026-2027. Uses Huawei Cloud services as described in the project documentation.
