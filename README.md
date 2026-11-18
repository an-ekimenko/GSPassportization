# Jck_GS_Pasportization

**Extracts, parses, and stores data about gas station attributes from a web API into an MS SQL staging environment for SAP BW/4HANA.**

## 📋 Description

The **Jck_GS_Pasportization** application automates the extraction, parsing, and storage of gas station complex attributes — including coordinates, addresses, fuel brands, and active service equipment statuses. The application retrieves raw JSON data from a remote web service, transforms the payloads defensively, and writes the records to a target MS SQL database. This environment serves as a high-performance intermediate staging area (PSA) for subsequent ETL loading into **SAP BW/4HANA**.

## 🚀 Features

* **Dependency Injection Architecture:** Powered by **Google Guice 7**. Promotes loose coupling across the network, serialization, and persistence layers by replacing anti-pattern static singletons and implicit state lifecycle bottlenecks with declarative, type-safe constructor and provider-based injection.
* **Resilient Network Infrastructure:** Utilizes a highly optimized **Apache HttpClient 5** infrastructure backed by a managed connection pool (`PoolingHttpClientConnectionManager`), establishing a scalable, high-throughput foundation engineered for high-concurrency enterprise web service interactions.
* **Automated Fault-Tolerance & Jittered Exponential Retry Policy:** Embeds a custom `ExponentialBackoffRetryStrategy` (extending `DefaultHttpRequestRetryStrategy`) to handle transient network issues, HTTP `429`/`5xx` responses, and drops with randomized jitter (`initial_timeout_ms`) and a total execution time budget (`max_timeout_ms`).
* **State-Aware Lazy Initialization:** Defers the final instantiation of the HTTP client until the exact moment of the first remote invocation to ensure the underlying `SSLContext` is initialized only after local keystore synchronization.
* **Dynamic TLS Certificate Lifecycle Management:** Integrates a custom `KeyStoreManager` and certificate updater that intercepts remote chains during TLS handshakes, updates local JVM `cacerts`, and hot-swaps the memory-resident `SSLContext` without restarts, routing `DEBUG` traces directly into application logs.
* **Fault-Tolerant JSON Deserialization:** Features a `GSP_DataDeserializer` that normalizes missing/null elements, handles malformed identifiers with overflow protection, filters `null` array items, and parses scientific notation while rejecting `NaN`/`Infinity` values.
* **Stream-Based Data Transformation:** Leverages Java Streams to map DTOs into Hibernate domain entities while filtering out records with missing/invalid business keys and eliminating duplicates prior to persistence.
* **High-Performance Staging Persistence:** Executes optimized, transaction-aware batch operations (`merge`, `flush`, `clear`) via the DAO layer to minimize database round-trips.
* **Pluggable Multi-Schema Authentication & OAuth2 Lifecycle:** Supports `Basic`, static `Bearer Token`, `OAuth2` (Client Credentials Grant with in-memory caching), and anonymous (`none`) schemes.
* **Configurable Transport Timeouts:** Enforces explicit TCP/TLS connection and socket response timeout thresholds (`connect_timeout_ms`, `response_timeout_ms`) via Apache HttpClient 5.
* **Automated Exchange Status Reporting:** Serializes execution summaries (`datetime`, `returned_code`, `records_processed`) into `exchange/result.yaml` upon completion.
* **Comprehensive Automated Test Suite:** Integrated testing matrix covering architectural verification, data-driven edge cases, and isolated slice-integration tests for stream transformations, invalid ID filtering, and deduplication boundaries.

## 🏗️ Shared Dependency & Execution Architecture

To eliminate library redundancy and optimize disk space usage across multiple integration applications, this project adopts a decoupled shared-dependency deployment model:

* **Centralized Dependencies:** All external runtime JAR libraries are compiled and isolated into a dedicated `dependencies` directory.

### 1. Directory Layout

The `dependencies/` folder is located **one level above** the application root directory (`Jck_GS_Pasportization`), serving as a common binary store for neighboring integration modules:

```text
shared-environment-root/
├── dependencies/               <-- Shared runtime external libraries (*.jar)
└── Jck_GS_Pasportization/      <-- This application root directory
    ├── config/                 <-- External environment configuration (Hibernate, API)
    ├── src/
    │   ├── main/               <-- Production source code
    │   └── test/               <-- Unit and pipeline integration tests
    ├── target/                 <-- Compiled artifacts and test reports
    └── pom.xml
```

*   **Classpath Decoupling:** The compiled application artifact excludes third-party binaries from its own internal structure, dynamically linking against the shared `../dependencies/` directory at runtime via classpath definitions inside the manifest.
---

## 📂 Project Structure & Configuration

The project follows a clean, layered architecture designed for deterministic batch processing:

### ⚙️ Configuration & Security Management
> ⚠️ **Security Note:** Production configuration files containing plain-text credentials (`properties.yaml` and `hibernate.cfg.xml`) are excluded from the repository via `.gitignore` and represented by safe `.dist` templates.

* **`config/properties.yaml.dist`** — Safe template for API authorization modes, targets, and connection parameters. Copy and rename to `properties.yaml` inside `config/`:

```yaml
gs_passportization-api:
  data_endpoint: https://your-api-domain/api/v1/data
  # Authentication strategy: none | basic | token | oauth2
  auth_type: oauth2

  connection:
    connect_timeout_ms: 50000
    response_timeout_ms: 120000

  auth:
    # Used only if auth_type: basic
    basic:
      username: YOUR_SERVICE_USERNAME
      password: YOUR_SERVICE_PASSWORD

    # Used only if auth_type: token (pre-generated static Bearer/API key)
    static_token: YOUR_STATIC_TOKEN

    # Used only if auth_type: oauth2 (Client Credentials Grant workflow)
    oauth2:
      token_endpoint: https://your-corporate-gateway-domain/identity/connect/token
      client_id: YOUR_CLIENT_ID
      client_secret: YOUR_CLIENT_SECRET
      scope: YOUR_SCOPE
      grant_type: client_credentials

  # Fault-tolerance retry boundary constraints based on exponential backoff rules:
  # max_timeout_ms     - Total maximum time budget (in ms) allocated to this specific worker
  #                      to fetch data from the API across the initial run and all retry attempts.
  # initial_timeout_ms - Base delay interval (in ms) before the first retry attempt.
  max_timeout_ms: 300000
  initial_timeout_ms: 10000
```

* **`config/hibernate.cfg.xml.dist`** — Safe template for MS SQL connection strings and Hibernate parameters (rename to `hibernate.cfg.xml`).
* **`gsp.config.ConfigPathResolver`** — Centralized path resolution utility for IDE and JAR contexts, validating files and provisioning `exchange/`.
* **`gsp.config.GSPApiConfig`** — DTO class for deserializing API connection settings and retry parameters from `properties.yaml`.
* **`gsp.config.YAMLConfigLoader`** — Jackson `YAMLFactory`-based loader implementing Fail-Fast semantics for configuration parsing.
* **`gsp.util.LogInitializer`** — Utility initializing `Log4j2/SLF4J` and `P6Spy` with cross-platform log routing via `${sys:log.dir}`.
* **`gsp.util.ResultFileWriter`** — Serializes execution summaries into `exchange/result.yaml` for downstream schedulers.
* **`src/main/resources/Log4j2.xml`** — Log4j2 configuration managing internal file routing and TLS debug-level boundaries.

### 🧪 Automated Quality Assurance Layer
* **`src/test/java/gsp/jackson/GSP_DataDeserializerTest`** — A highly parameterized test specification. It evaluates the custom Jackson deserializer across multiple edge cases including fuzzy string coordinate normalization, scientific notation and `NaN`/`Infinity` filtering, absolute integer ID extractions, null-safe fuel array data-cleansing routines, and silent structural primitive type overflow constraints.
* **`src/test/java/gsp/network/GSP_HttpClientTest`** — An infrastructure unit test suite that isolates remote transport execution using managed test doubles. It intercepts internal request states via argument captors to validate the programmatic precision of Basic, Bearer, and OAuth2 auth header constructions, HTTP error handling, and TLS certificate update hooks while completely isolating external JVM keystore initialization overhead.
* **`src/test/java/gsp/GSP_AppTest`** — An integration pipeline test verifying the core application orchestrator. It mocks remote transport boundaries to confirm that the functional streaming layers flawlessly filter out records with missing/invalid identifiers and duplicated upstream enterprise business keys before emitting finalized bulk units to the database subsystem.


### 🌐 Dependency Injection & Network Transport Layers
* **`gsp.MainLauncher`** — The definitive bootstrapper and application entry point. It orchestrates early infrastructure initialization by bootstrapping the logging subsystem, builds the global Google Guice dependency injection container, invokes the main orchestration pipeline, and explicitly manages process exit codes (`0` or `1`) for seamless integration with external OS schedulers and enterprise cron triggers.
* **`gsp.GSPAppModule`** — The core Dependency Injection container powered by Google Guice. It centrally configures and exposes high-level infrastructure singletons including application configuration profiles, an optimized Hibernate `SessionFactory` pool managed with safe programmatic teardown mechanics, a customized Jackson `ObjectMapper`, and a pre-configured `HttpClientBuilder` tailored with `ExponentialBackoffRetryStrategy` defaults.
* **`gsp.GSP_App`** — Main application orchestrator coordinating the end-to-end data pipeline. It orchestrates the flow of data by triggering the network fetch, managing target deserialization types, filtering out records with missing or invalid business keys, executing deterministic in-memory deduplication via functional pipelines, and delegating clean persistence units to the database layer.
* **`gsp.network.ExponentialBackoffRetryStrategy`** — A custom retry strategy extending `DefaultHttpRequestRetryStrategy` that handles transient network I/O errors and retryable HTTP responses (`429`, `5xx`) using an exponential backoff algorithm with randomized jitter, `Retry-After` header support, and a strict overall execution time budget (`max_timeout_ms`).
* **`gsp.network.GSP_HttpClient`** — A high-performance network transport service encapsulating a managed `CloseableHttpClient` connection pool. It abstracts low-level network operations from business logic, leveraging a thread-safe lazy-initialization pattern to defer client instantiation until upstream TLS certificates are verified, and dynamically generates request-scoped authorization headers (`Basic`, `Bearer`, `OAuth2` with in-memory token caching, or `none`).


### 💾 Domain Model & Persistence Layer

* **`gsp.hibernate.GSP_DAO`** — Data Access Object (DAO) utility managed as a Guice singleton. It implements transactional multi-record batch processing using periodic cache synchronization primitives (`merge`, `flush`, `clear`) to maximize throughput into MS SQL while enforcing flat deterministic memory limits.
* **`gsp.model.GSP_Entity`** — A Hibernate entity class representing the target `GS_Passportization` staging database table, explicitly structured using standard JPA `@Table` and `@Column` mapping metadata.
* **`gsp.model.GSP_Data`** — A DTO class representing a gas station complex object mapped directly from the raw top-level JSON response arrays.
* **`gsp.model.ExecutionResult`** — A DTO class modeling the execution summary payload (`datetime`, `returned_code`, `records_processed`) written to `exchange/result.yaml` upon pipeline completion or failure.
* **`gsp.jackson.GSP_DataDeserializer`** — A fault-tolerant Jackson deserializer implementing safe data-extraction strategies for upstream API payloads. It ensures strict mapping of missing, null, or malformed JSON nodes to predictable Java structures, filters null array elements, rejects non-finite coordinates (`NaN`/`Infinity`), and standardizes inconsistent numeric data formats with overflow protection.

### 🔄 Orchestration & Lifecycle Bootstrapping

* **`gsp.GSP_App`** — The primary high-level application orchestrator. Coordinates the data pipeline linearly (`Download payload` -> `Parse DTO list` -> `Filter invalid IDs and clean duplicate keys via Streams` -> `Database batch flush`) while keeping components decoupled.
* **`gsp.MainLauncher`** — The definitive main application bootstrapper. It establishes the sequential application startup timeline: sets up diagnostic logging contexts first, boots up the Guice container second, runs the orchestration pipeline third, and communicates final state to the OS/scheduler by explicitly calling `System.exit()` codes.

### 📊 Generated Artifacts

* **`logs/`** — Created automatically in the application root directory during runtime. Contains standalone execution logs (`commons.log`) and database interaction telemetry (`p6spy.log`).
* **`exchange/result.yaml`** — Generated automatically inside the `exchange/` directory upon execution completion. Stores the timestamp (`datetime`), process status (`returned_code`), and total deduplicated records persisted (`records_processed`) for external schedulers and downstream ETL workflows:

```yaml
datetime: "2026-11-18 13:41:22"
returned_code: 0
records_processed: 577
```

## 🧪 Testing Framework & Test Scenarios

The project includes a comprehensive test suite built on top of the **JUnit 5 (Jupiter)** platform and **Mockito** framework to validate data parsing resilience, network infrastructure robustness, and end-to-end synchronization pipeline integrity.

### 1. Test Architecture & Stack

* **JUnit 5:** Drives unit and parameterized testing execution.
* **Mockito (`MockitoExtension`):** Isolates components under test by mocking heavy ecosystem objects (`GSP_HttpClient`, `GSP_DAO`, `GSPApiConfig`, `HttpClientBuilder`).
* **MockedStatic (Mockito):** Intercepts global static infrastructure methods (e.g., `KeyStoreManager.getSSLContext()`, `CertUpdater`) to securely bypass external TLS certificate routine checks in local test environments.

---

### 2. Test Scenarios

The test suite is structured around three core logical components:

#### A. Data Parsing Layer (`GSP_DataDeserializerTest`)
Validates the custom Jackson deserializer (`gsp.jackson.GSP_DataDeserializer`) and its ability to safely process malformed payloads without runtime crashes:
* **Standard Payload Mapping:** Verifies correct mapping of pristine production-grade JSON strings into DTO structures, checking identifiers, joined fuel array strings, and coordinates.
* **Coordinate Normalization & Non-Finite Filtering:** Parameterized check using different variations of coordinates (floating points, commas, spaces, extra text tokens, and scientific notation like `1e500`) to ensure valid double extraction while rejecting `NaN` and `Infinity` values.
* **Identifier Extraction & Overflow Resilience:** Validates parsing of messy text tokens and negative business keys into absolute integer values. Ensures extreme overflows (exceeding `Integer.MAX_VALUE` or `Integer.MIN_VALUE`) are caught safely, returning `null` instead of corrupted records.
* **Fuel Array & Null-Safe Sanitization:** Verifies that `null`, blank, and non-scalar elements inside the `fuels` array are filtered out cleanly, and missing or mismatched JSON field types fall back safely.

#### B. Network & Infrastructure Layer (`GSP_HttpClientTest`)
Ensures request construction rules, error handling, and authentication headers match API security strategies:
* **Basic Authentication Scheme:** Asserts correct generation of the standard `Authorization` header containing the Base64-encoded `username:password` string.
* **Bearer Token Authentication:** Validates precise inclusion of active static API keys as `Bearer` tokens in outgoing request headers.
* **Public Access Optimization:** Assures the `Authorization` header is completely omitted when the auth strategy profile is set to `none`.
* **OAuth2 Client Credentials Flow:** Validates token exchange requests (`grant_type=client_credentials`), JSON `access_token` extraction, in-memory token caching across subsequent requests, and automatic `Bearer` header injection.
* **HTTP Error & TLS Lifecycle Verification:** Verifies that server error codes (`5xx`) and empty `200 OK` bodies raise descriptive runtime exceptions, and confirms that appropriate `CertUpdater` hooks are invoked during TLS initialization.
* **Configuration Validation:** Verifies that Fail-Fast `IllegalStateException` instances are raised when `data_endpoint`, `auth_type`, or required credentials are missing or unsupported.

#### C. Integration Pipeline Layer (`GSP_AppTest`)
Verifies end-to-end processing and correct database interaction constraints:
* **Successful Sync & Deduplication:** Simulates multi-item JSON arrays with duplicate business keys (matching `complex` IDs) and validates that the pipeline applies stream filtering to eliminate redundancy before triggering batch persistence via `GSP_DAO`.
* **Invalid ID Filtering:** Verifies that records with missing or unparseable (`null`) `complex` identifiers are safely skipped and logged without aborting valid items in the batch.
* **Empty Payload Protection:** Ensures that blank or whitespace-only server responses early abort the execution path and throw an error, preventing downstream components from running.
* **Zero Record Boundaries:** Verifies that valid empty arrays `[]` from the API complete gracefully while entirely skipping unnecessary database write activities.

---

### 3. Executing Tests

To run the complete test suite and generate execution verification reports, run the standard Maven phase from the project directory:

```bash
mvn clean test
```

---

## 🛠 Prerequisites

- **Java:** JDK 17 or higher *(required by Google Guice 7, Hibernate 6+, modern TLS 1.3 security providers, and LTS runtime performance)*
- **Database:** MS SQL Server
- **Build System:** Apache Maven 3.8+


## 💻 Deployment & Running

### 1. Prepare Environment Configurations
Since production credentials are safe from tracking via `.gitignore`, initialize your active operational profiles from the provided templates at the project root:
* Copy `config/properties.yaml.dist` to a new file named `properties.yaml` inside the root `config/` directory and fill in your target API configurations.
* Copy `config/hibernate.cfg.xml.dist` to a new file named `hibernate.cfg.xml` inside the root `config/` directory and update the MS SQL connection credentials.

### 2. Build the Application
Run the standard Maven package phase to compile the application and collect shared dependencies:
```bash
mvn clean package
```
This compilation setup automatically executes the following:
1. Compiles the Java source files under JDK 17 specifications.
2. Triggers `maven-dependency-plugin` to isolate all required third-party binaries into the `../dependencies/` shared directory.
3. Generates the final application artifact inside the `target/` directory with a pre-configured manifest `Class-Path` referencing the parent directory.

### 3. Run the Application

Run the application using the Java CLI. Thanks to the automated manifest configuration in `pom.xml`, the classpath linking to the shared `dependencies/` folder is resolved out of the box, while the logging engine dynamically routes output paths relative to your active execution scope.

By default, the application initializes and writes all runtime traces into a local `logs/` directory created in the root folder of execution. This path can be dynamically overridden at runtime by supplying the `-Dlog.dir` system property flag to the JVM configuration footprint:

```bash
# 1. Execute utilizing the default local logs directory (./logs/)
java -jar /path/to/Jck_GSPassportization.jar

# 2. Alternative: Execute with explicit log directory overriding via JVM System Properties
java -Dlog.dir=/var/log/gsp_applications -jar /path/to/Jck_GSPassportization.jar
```
