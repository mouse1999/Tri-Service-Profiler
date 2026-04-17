
# Tri-Service Profiler 🧙‍♂️

A production-grade Spring Boot microservice designed to orchestrate data from **Genderize**, **Agify**, and **Nationalize** APIs. The system builds comprehensive user profiles, handles concurrency via parallel processing, and ensures data persistence with idempotent logic.

## 📋 Task Requirements & Implementation
- **Parallel Orchestration:** Uses `CompletableFuture` to fetch data from three upstream services simultaneously.
- **Idempotency:** Checks the database before calling external APIs. If a profile for the name exists, it returns the cached record.
- **Classification Logic:**
    - **Age:** 0–12 (child), 13–19 (teenager), 20–59 (adult), 60+ (senior).
    - **Nationality:** Extracts the country with the highest probability from the Nationalize response.
- **Standards:** UUID v7 for IDs, UTC ISO 8601 for timestamps, and `Access-Control-Allow-Origin: *` for all responses.

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
    "sample_size": 1234,
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
  "data": {
    "id": "018e6a1b-7d4a-7c91-9c2a-1f0a8e5b6d12",
    "name": "ella",
    "gender": "female",
    "age": 46,
    "age_group": "adult",
    "country_id": "CD",
    "country_probability": 0.85,
    "created_at": "2026-04-17T14:30:00Z"
  }
}
```

### 3. Get All Profiles (with Filtering)
**GET** `/api/profiles?gender=male&country_id=NG&age_group=adult`
- **Success Response (200 OK):**
```json
{
  "status": "success",
  "count": 2,
  "data": [
    {
      "id": "018e6a1b-...",
      "name": "emmanuel",
      "gender": "male",
      "age": 25,
      "age_group": "adult",
      "country_id": "NG"
    },
    {
      "id": "018e6a1c-...",
      "name": "sarah",
      "gender": "female",
      "age": 28,
      "age_group": "adult",
      "country_id": "US"
    }
  ]
}
```

### 4. Delete Profile
**DELETE** `/api/profiles/{id}`
- **Success Response:** `204 No Content`

## ⚠️ Error Responses
All error responses follow the structure: `{"status": "error", "message": "<message>"}`

| Status Code | Message | Trigger Condition |
| :--- | :--- | :--- |
| **400** | `Missing or empty name` | Request body name is null or blank |
| **404** | `Profile not found` | UUID does not exist in the database |
| **422** | `Invalid type` | Name contains numbers or special characters |
| **502** | `Genderize returned an invalid response` | API returns gender: null or count: 0 |
| **502** | `Agify returned an invalid response` | API returns age: null |
| **502** | `Nationalize returned an invalid response` | API returns empty country list |

## 💻 Local Setup & Running
1. **Prerequisites:** Java 21, Maven 3.9+.
2. **Build:** `mvn clean install`
3. **Run Locally:** `mvn spring-boot:run`
4. **Local DB:** The app uses H2 for local dev. Access at `http://localhost:8080/h2-console`.

## 🛠 Deployment Configuration
For **Railway** or **Render**, set these environment variables:
- `SPRING_PROFILES_ACTIVE`: `prod`
- `DATABASE_URL`: Your PostgreSQL link.
- `JAVA_VERSION`: `21`


