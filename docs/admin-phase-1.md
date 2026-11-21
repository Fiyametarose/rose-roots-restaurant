# Admin Phase 1

The Customer application remains at the Gradle root. The Kitchen application is
`:kitchen-app`, with Admin as a manager-only workspace within the same executable.
The `:server` module is the Spring Boot server entry point specified by Project Plan
1.1. The Admin branch had no server implementation, database migrations, shared DTO
module or authoritative operational endpoints at the time of the audit.

## Running locally

Use Java 21 and the root Gradle wrapper. Configure the server-side account with
`ROSE_ADMIN_MANAGER_USERNAME` and `ROSE_ADMIN_MANAGER_PASSWORD_HASH`.
Provide a BCrypt hash generated outside the application from a unique, strong
password. The password itself is entered in the JavaFX sign-in screen.
For a separate limited account, configure `ROSE_ADMIN_KITCHEN_USERNAME` and
`ROSE_ADMIN_KITCHEN_PASSWORD_HASH`. With both pairs absent, the application has
zero configured accounts. The configuration can be replaced by the shared
server's `UserDetailsService` and matching password encoder.

1. Run `.\gradlew.bat :server:bootRun`.
2. In a second terminal run `.\gradlew.bat :kitchen-app:run`.
3. Click **Manager sign-in** in the Kitchen sidebar. Use **Sign out** to end the
   server session and **Back to Kitchen** to dismiss Admin mode.

The server binds to `127.0.0.1:8080` by default. Within that local configuration
the desktop client allows loopback HTTP. For the lecturer demonstration, use
the [two-PC Windows guide](two-pc-demonstration.md) and its TLS-enabled
`lan` profile. Set `ROSE_BACKEND_URL` to the HTTPS origin. An explicit
`ROSE_ADMIN_SERVER_URL` is an Admin-only override. The server uses
cookie-only sessions and a 15-minute inactivity timeout. The Admin workspace
also signs out after 15 minutes without user interaction. Sign-in and sign-out
require the Spring Security CSRF token.

Run `.\gradlew.bat clean build` to build and test Customer, Kitchen and the server.

## Dashboard integration

All four routes are under `/api/v1/admin`.

| Request | Result |
| --- | --- |
| `GET /csrf` | CSRF token as plain text |
| `POST /login` | Form fields `username` and `password`. Send the token in `X-CSRF-TOKEN`. A manager receives a session and HTTP 204. |
| `GET /dashboard` | Manager session required. Returns the restaurant overview as JSON. |
| `POST /logout` | Fetch a fresh CSRF token, then send it in `X-CSRF-TOKEN`. HTTP 204 confirms logout. |

Keep the server's session cookie across requests. Login returns HTTP 401 for
invalid credentials and 403 for an authenticated account without the Manager
role. Obtain a fresh CSRF token after authenticating because Spring Security
rotates it when the session changes.

Implement the server-side `DashboardSource` bean when the real order, dining
session, payment, catalog availability, feedback and assistance services are
available. A missing source or a null field maps to **Unavailable**. An empty
list represents a successfully loaded section with no results, and zero is a
valid metric. Supply the recent orders for the current business day with
statuses and display amounts from the authoritative services. The existing
Kitchen board uses a local ticket repository, including its local Rush
profiles, and those profiles have no effect on the Admin server session.

## UI verification

The [manager sign-in](phase1-screenshots/manager-sign-in.png),
[overview](phase1-screenshots/restaurant-overview.png), and
[narrow overview](phase1-screenshots/restaurant-overview-narrow.png) were
captured from the running JavaFX application against the local Spring Boot
server. The overview screenshots show the server-connected state before
operational sources are integrated.
