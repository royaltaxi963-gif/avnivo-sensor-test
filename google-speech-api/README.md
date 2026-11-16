# AVNIVO Google Speech V2 — staging only

Separate Cloud Run backend. Existing Groq and browser speech are untouched.

* Set GOOGLE_CLOUD_PROJECT to the project ID.
* Enable Cloud Speech-to-Text API; attach a Cloud Run service account with speech recognition permission.
* Deploy from this folder using the Dockerfile, with Cloud Run max instances constrained and monitoring configured.
* Public browser requests require a dedicated abuse-prevention layer before production. Origin and CORS are **not** authentication.
* POST /api/transcribe: multipart audio (webm/opus or ogg/opus), language (uz, ru, en, tr).
* GET /health: configuration indicator only, not proof of a working provider call.
* Google synchronous recognize has duration limits. Long recordings need a separate streaming/batch implementation.
* Verify Uzbek recognition and billing with an actual staging audio sample before connecting the UI.
