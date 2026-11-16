-- MicroWatch database (MySQL 8)
CREATE DATABASE IF NOT EXISTS microwatch CHARACTER SET utf8mb4;
USE microwatch;

-- Monitoring locations (used by the GIS map and upstream/downstream comparison)
CREATE TABLE sites (
    site_id INT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(10) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    latitude DECIMAL(9 , 6 ) NOT NULL,
    longitude DECIMAL(9 , 6 ) NOT NULL,
    position ENUM('upstream', 'downstream', 'other') NOT NULL DEFAULT 'other'
);

-- One row per water sample. sample_id is THE single ID used everywhere.
CREATE TABLE samples (
  sample_id       VARCHAR(24) PRIMARY KEY,           -- MW-S03-20261117-0001
  site_id         INT NOT NULL,
  water_source    VARCHAR(100),
  volume_ml       INT NOT NULL,
  collected_at    DATETIME NOT NULL,
  referral_status ENUM('none','pending','referred','confirmed') NOT NULL DEFAULT 'none',
  FOREIGN KEY (site_id) REFERENCES sites(site_id)
);

-- The three sampling steps, all linked to the same sample_id
CREATE TABLE sample_steps (
  id           INT AUTO_INCREMENT PRIMARY KEY,
  sample_id    VARCHAR(24) NOT NULL,
  step_no      TINYINT NOT NULL,                     -- 1 collection, 2 filtration, 3 imaging
  step_name    VARCHAR(30) NOT NULL,
  status       ENUM('pending','done') NOT NULL DEFAULT 'pending',
  notes        VARCHAR(255),
  completed_at DATETIME,
  UNIQUE (sample_id, step_no),
  CHECK (step_no BETWEEN 1 AND 3),
  FOREIGN KEY (sample_id) REFERENCES samples(sample_id)
);

-- Water-quality readings (from IoTDA or manual entry)
CREATE TABLE sensor_readings (
  id            INT AUTO_INCREMENT PRIMARY KEY,
  sample_id     VARCHAR(24) NOT NULL,
  ph            DECIMAL(4,2),
  turbidity     DECIMAL(8,2),
  temperature   DECIMAL(5,2),
  tds           DECIMAL(8,2),
  dissolved_oxygen DECIMAL(5,2),
  source        ENUM('real','simulated') NOT NULL,   -- always be honest about this
  recorded_at   DATETIME NOT NULL,
  FOREIGN KEY (sample_id) REFERENCES samples(sample_id)
);

-- AI results
CREATE TABLE predictions (
  id              INT AUTO_INCREMENT PRIMARY KEY,
  sample_id       VARCHAR(24) NOT NULL,
  model_version   VARCHAR(30) NOT NULL,
  image_path      VARCHAR(255),
  particle_count  INT,
  confidence      DECIMAL(5,4),                      -- 0.0000 to 1.0000
  screening_index DECIMAL(5,2),                      -- project-defined 0-100
  needs_review    BOOLEAN NOT NULL DEFAULT FALSE,    -- low confidence => expert
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (sample_id) REFERENCES samples(sample_id)
);

-- Expert review (feeds active learning later)
CREATE TABLE expert_reviews (
  id            INT AUTO_INCREMENT PRIMARY KEY,
  prediction_id INT NOT NULL,
  reviewer      VARCHAR(100) NOT NULL,
  label         VARCHAR(30) NOT NULL,                -- fiber/fragment/film/pellet/foam/unknown
  comment       VARCHAR(255),
  reviewed_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (prediction_id) REFERENCES predictions(id)
);

-- Model versions (shows retraining changed the version)
CREATE TABLE model_versions (
  version     VARCHAR(30) PRIMARY KEY,
  f1_score    DECIMAL(5,4),
  size_mb     DECIMAL(8,2),
  latency_ms  DECIMAL(8,2),
  trained_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
