# Admin Phase 2B food photographs

The Add / Edit Dish screen uses the existing catalog and Manager session.
Choose or replace a JPEG or PNG photo, inspect the preview, then open the
Image Editor to crop, zoom, reposition, rotate in quarter turns, or adjust
brightness and contrast. The editor includes the Customer card ratio
(1.87:1), the dish detail ratio (1.53:1), square, and original proportions.
Apply stages the rendered pixels. Save Dish publishes them. Cancelling the
editor or the dish form preserves the saved photo. Remove Photo clears the
dish reference on Save Dish.

## Storage and deployment

Set `ROSE_IMAGE_DIR` in the Spring Boot environment to a dedicated,
persistent directory on PC 2. It defaults to
`<user.home>/.rose-roots/menu-images`. For example:

```powershell
$env:ROSE_IMAGE_DIR = 'C:\RoseRoots\menu-images'
```

Use the existing `catalog` profile, or `catalog,lan` for HTTPS. This uses
the same service, backend origin and Rose & Roots PostgreSQL database.
Keep the image directory writable by the backend account. Keep the directory
and a consistent database backup together when moving or restoring the server.

Flyway `V3__menu_images.sql` adds `catalog_menu_image` with UUID, generated
storage reference, type, dimensions, byte size, creation time and Manager
identity. Original upload names and paths are discarded. The server
checks the actual format before decoding and checks source dimensions
before reading pixels. It accepts JPEG and PNG, up to 8 MiB, 6000 pixels
per edge and 16 megapixels total. It scales the longest output edge to at
most 1600 pixels, flattens transparency onto white, and re-encodes to JPEG.
The editor uses the same input limits and writes its crop and adjustments
into the encoded pixels. WebP is outside the verified decoder set.

The backend writes a temporary file and atomically moves it to a generated
UUID filename before committing metadata. A confirmed database rollback
triggers file cleanup. Dish publication is a separate transaction. Until it
commits, a replacement or removal leaves the previously saved reference
intact. Retrying a failed save in the same editor reuses the completed
upload when available. An abandoned upload, an interrupted response or an
old replacement can leave an unreferenced file and metadata. The application
retains those resources and performs no automatic deletion. For maintenance,
pause uploads, take a backup, identify references across all catalog items
including archived ones, and review unreferenced resources under a retention
policy before removing metadata and files. Existing legacy image references
can be kept on the same dish. New references must identify uploaded images.

## HTTP contract

| Method and route | Access and behavior |
| --- | --- |
| `POST /api/v1/admin/menu-images` | Manager session plus `X-CSRF-TOKEN`. Body is the raw JPEG or PNG file bytes. The server checks content regardless of the declared MIME type. Success returns 201 and a relative `Location` header. |
| `GET /api/v1/menu-images/{id}` | Public. Returns normalized `image/jpeg` bytes for the UUID with `nosniff` and immutable caching. Unknown IDs return 404. |
| `POST /api/v1/admin/menu-items` or `PATCH /api/v1/admin/menu-items/{id}` | Set `imageReference` to the upload response reference. On the complete dish form, null or omission clears the reference. |
| `GET /api/v1/menu` and `GET /api/v1/menu-items` | Published dishes include the portable `imageReference` value. |

Example upload response:

```json
{
  "id": "22780151-e925-4daa-b56e-802489c2e08e",
  "imageReference": "/api/v1/menu-images/22780151-e925-4daa-b56e-802489c2e08e",
  "contentType": "image/jpeg",
  "width": 1200,
  "height": 642,
  "fileSize": 134829,
  "createdAt": "2026-11-21T12:00:00Z"
}
```

Oversized data or dimensions return 413, unsupported content returns 415,
and decoding errors in a recognized format return 400. New dish references
cannot contain filesystem paths, external URLs, or unknown image IDs.

The Customer adapter should resolve `/api/v1/menu-images/{id}` against the
configured backend origin before passing the URI to its existing
`MenuPhoto` component. Bundled image resources can continue using their
existing conventions. Each replacement URL has a new UUID, so the view can
load it independently of the cached predecessor. No Customer transport or
two-PC topology changes are needed for the image endpoint itself.

## Verification

The Gradle build and both Windows launcher distributions were checked.
The PostgreSQL image tests use a generated schema and a temporary image
directory. They verify authorization, actual image content and limits,
publication, replacement, failed saves, and byte-for-byte retrieval after
restarting Spring Boot with the same database and storage. UI captures
use disposable records and trusted local HTTPS.

- [Image Editor](phase2b-screenshots/image-editor.png)
- [Add / Edit Dish with the staged crop](phase2b-screenshots/dish-edited-draft.png)
- [Add / Edit Dish with a saved photograph](phase2b-screenshots/dish-saved-photo.png)
