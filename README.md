
# Tri-Service Profiler 🧙‍♂️

The Tri-Service Profiler is a high-performance Spring Boot microservice engineered to solve the problem of fragmented identity data. By orchestrating real-time data from three distinct external providers (Genderize, Agify, and Nationalize), the system synthesizes a comprehensive user profile from a single name input.

What began as a data collection tool has evolved into a sophisticated Profile Intelligence Engine. It features a custom Natural Language Query (NLQ) Interpreter and a dynamic filtering system that allows users to explore large datasets using both structured parameters and human-like search phrases.

## 📋 Task Requirements & Implementation

### Phase 1: Core Orchestration
- **Parallel Orchestration:** Uses `CompletableFuture` to fetch data from three upstream services simultaneously.
- **Idempotency:** Checks the database before calling external APIs to prevent redundant calls.
- **Classification Logic:**
    - **Age:** 0–12 (child), 13–19 (teenager), 20–59 (adult), 60+ (senior).
    - **Nationality:** Extracts the highest probability country.
- **Standards:** UUID v7, UTC ISO 8601, and full CORS support.

### Phase 2: Dynamic Filtering & NLP
- **Query Interpreter:** A custom engine that parses "Natural Language Queries" (NLQ) to extract intent (e.g., "young males from Nigeria").
- **JPA Specifications:** Implemented a dynamic query builder using the Criteria API to handle possible filter combinations.
- **Advanced Filtering:** Added support for ranges (`min_age`, `max_age`) and probability thresholds (`min_gender_probability`).
- **Performance:** Implemented composite indexing on high-traffic columns (`gender`, `country_id`, `age_group`) to ensure sub-millisecond query execution.


## 🔌 API Documentation

### 1. Create Profile
**POST** `/api/profiles`
- **Request Body:** `{ "name": "ella" }`
- **Success Response (201 Created):**
```json
{
  "status": "success",
  "data": {
    "id": "018e6a1b-7d4a-7c91-9c2a-1f0a8e5b6d12",
    "name": "ella",
    "gender": "female",
    "gender_probability": 0.99,
    "age": 46,
    "age_group": "adult",
    "country_id": "CD",
    "country_probability": 0.85,
    "created_at": "2026-04-17T14:30:00Z"
  }
}
```

### 2. Get Single Profile
**GET** `/api/profiles/{id}`
- **Success Response (200 OK):**
```json
{
  "status": "success",
  "data": { "id": "...", "name": "ella", "gender": "female" }
}
```

### 3. Get All Profiles (Advanced Filtering)
**GET** `/api/profiles?gender=male&min_age=20&country_id=NG`
- **Success Response (200 OK):**
```json
{
  "status": "success",
  "message": "Profiles retrieved successfully",
  "data": [
    {
      "id": "018f3a1b...",
      "name": "chidi",
      "gender": "male",
      "age": 28,
      "age_group": "adult",
      "country_id": "NG"
    }
  ],
  "pagination": {
    "current_page": 1,
    "total_pages": 1,
    "total_elements": 1,
    "limit": 10
  }
}
```

### 4. Natural Language Search (NLP)
**GET** `/api/profiles/search?q=young females from nigeria`
- **Logic:** The `QueryInterpreter` maps "young" to `max_age=18`, "females" to `gender=female`, and "nigeria" to `country_id=NG`.
- **Success Response (200 OK):**
```json
{
  "status": "success",
  "message": "Search results for: young females from nigeria",
  "data": [
    {
      "id": "018f3a5c...",
      "name": "amaka",
      "gender": "female",
      "age": 17,
      "age_group": "teenager",
      "country_id": "NG"
    }
  ],
  "pagination": {
    "current_page": 1,
    "total_pages": 5,
    "total_elements": 48,
    "limit": 10
  }
}
```

### 5. Delete Profile
**DELETE** `/api/profiles/{id}`
- **Success Response:** `204 No Content`


## ⚠️ Error Responses
All error responses follow the structure: `{"status": "error", "message": "<message>"}`


| Status Code | Final Message Displayed to User | Technical Trigger / Cause |
| :--- | :--- | :--- |
| **400** | `Invalid query parameter` | `min_age` > `max_age` logic conflict. |
| **400** | `Invalid query parameter` | Gender value is not 'male' or 'female'. |
| **400** | `Invalid query parameter` | `country_id` length is not exactly 2 characters. |
| **400** | `Invalid query parameter` | Probability value is outside the 0.0 - 1.0 range. |
| **400** | `Invalid query parameter` | The required search query `q` is missing or blank. |
| **400** | `Invalid query parameter` | NLQ input exceeds the 100-character safety limit. |
| **404** | `Profile not found` | Specific UUID does not exist in the database. |
| **404** | `Profile not found` | Requested pagination page is empty or out of range. |
| **422** | `Invalid type` | POST request `name` contains numbers or symbols. |
| **500** | `An unexpected server error occurred` | System-level failures (e.g., Database connection lost). |



---

## 🏗 Data Ingestion & Seeding Pipeline

The system features a robust **DataSeeder** designed to prepopulate the database from an external JSON source. This ensures that the environment is "demo-ready" immediately upon startup.

### 1. The JSON Parser
Unlike basic seeders that map files directly to objects, this implementation uses a **Jackson JsonNode Tree** approach.
* **Structure Resilience:** It targets a specific `"profiles"` root key within `seed_profiles.json`. This allows the JSON file to contain metadata or other nested objects without breaking the ingestion logic.
* **Stream Processing:** Uses `InputStream` to handle the file efficiently, preventing memory overhead.

### 2. Intelligent Ingestion Logic
The seeder doesn't just copy data; it performs **Data Enrichment** and **Validation** on the fly:
* **Idempotent Checks:** Uses `existsByName()` to prevent duplicate entries if the application is restarted.
* **Automatic UUID v7 Generation:** Every seeded record is assigned a time-ordered **UUID v7** using the `Generators.timeBasedEpochGenerator()`.
* **Dynamic Classification:** If a seed record provides an `age` but no `age_group`, the system automatically calculates the category (child, adult, etc.) using the `AgeCategory` enum logic.
* **Search Normalization:** Forces all names to lowercase during ingestion to ensure they are indexed correctly for the Search Engine.

### 3. Usage
To update the system's starting data, modify:
`src/main/resources/seed_profiles.json`
```json
{
  "profiles": [
    {
      "name": "Chidi",
      "gender": "male",
      "age": 28,
      "country_id": "NG",
      "gender_probability": 0.98,
      "country_probability": 0.99
    }
  ]
}
```

---

## 🛠 Technical Challenges & Lessons Learned

I encountered several "human" errors during Task 2 that required deep-diving into Spring Boot's internal mechanics:

### 1. The "Snake-to-Camel" Mapping Gap
**Problem:** The API used snake_case following web standards, but Java DTO used camelCase. Using `@JsonProperty` worked for POST bodies, but **failed for GET query parameters** because Spring's Data Binder ignores Jackson annotations.
**Solution:** I aligned the `QueryCriteria` DTO fields to use `snake_case` exactly. We then manually mapped these to the `camelCase` entity fields inside the `ProfileSpecification` using `root.get("countryId")`.

### 2. The Sorting "PropertyReferenceException"
**Problem:** Sending `?sort_by=created_at` crashed the app with a 500 error.
**Why:** JPA looks for a Java field named exactly `created_at`. Since our field was `createdAt`, it couldn't find a match.
**Solution:** I standardized the `sort_by` default value to `createdAt` and implemented a `validateSortField` check to ensure only valid Java field names are passed to the `Pageable` object.

### 3. The "Required Parameter" Bottleneck
**Problem:** Adding `@RequestParam String country_id` to the controller caused searches for age or gender to fail if the country wasn't provided.
**Solution:** I moved all filters into a single `QueryCriteria` DTO. By passing a POJO to the controller instead of individual params, Spring treats all fields as optional by default, making the API much more flexible.

### 4. Database Case Sensitivity
**Problem:** Filtering for `country_id=au` (lowercase) returned zero results because the database stored `AU`.
**Solution:** Implemented normalization logic in the DTO's `validate()` method to force all 2-letter ISO codes to Uppercase before they hit the repository.

---

## 💻 Local Setup & Running
1. **Prerequisites:** Java 21, Maven 3.9+.
2. **Build:** `mvn clean install`
3. **Run Locally:** `mvn spring-boot:run`
4. **Local DB:** Access H2 at `http://localhost:8080/h2-console`.
    - **JDBC URL:** `jdbc:h2:mem:profilerdb`
    - **Credentials:** `sa` / `password`





