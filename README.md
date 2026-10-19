# BucketBanish

A local web app that finds photos in S3 you probably don't need, and shows them side by side so you can decide:

- **Burst shots**: photos from the same camera taken within a few seconds of each other that also *look* alike
  (perceptual dHash).
- **Same byte size**, within one bucket and across buckets. Groups are labelled IDENTICAL or UNVERIFIED using
  S3 ETags (MD5), or SHA-256 with verification turned on.
- **Same filename in more than one bucket**.

BucketBanish is **read-only and never deletes anything**. You tick the photos to banish, and it builds a removal
report: bucket, region, folder, filename, size, upload date, ETag and a direct AWS console link for each one.
You then delete the files yourself in your S3 provider's web console.

## Demo

▶ **[Watch the demo video](BucketBanish-demo.mp4)** (75 s, no audio). It scans DigitalOcean Spaces buckets
(7,769 photos), reviews the burst sets it found, and then removes one duplicate by hand in the provider's console.

## Run

Requires Java 21.

```bash
./gradlew bootRun                    # then open http://localhost:8080
# or
./gradlew bootJar && java -jar build/libs/bucketbanish-0.2.0.jar
```

It binds to `127.0.0.1` only, because the API can read anything your AWS credentials can.

### Try it without S3

Point it at a local folder. Each subfolder acts as a bucket:

```bash
./gradlew bootRun --args='--bucketbanish.local-root=/path/to/photos'
# /path/to/photos/family-photos/...  ->  s3://family-photos
# /path/to/photos/backup-photos/...  ->  s3://backup-photos
```

## Configuration

### Connection details (gitignored)

Put the endpoint, region and keys for the instance you want in `bucketbanish.local.properties` in the project
directory, which is the directory you start the app from. This file is gitignored and loaded automatically.

```bash
cp bucketbanish.local.properties.example bucketbanish.local.properties
# edit: bucketbanish.endpoint, bucketbanish.region, bucketbanish.access-key-id, bucketbanish.secret-access-key
./gradlew bootRun
```

When `access-key-id` is set, the app uses only those keys and ignores `~/.aws/credentials` and `AWS_*` env vars.
Without them, it falls back to `bucketbanish.profile`, then to the standard AWS credential chain. The page header
shows which endpoint and credential source are active. The key is masked, and the secret is never shown or logged.

**Several instances:** create one file per instance, for example `bucketbanish.local-wasabi.properties` or
`bucketbanish.local-prod.properties` (also gitignored). Pick one with a Spring profile:

```bash
./gradlew bootRun --args='--spring.profiles.active=wasabi'
```

Values in a profile file override `bucketbanish.local.properties`, so shared settings can live in the main file.

### All settings

You can also pass any setting on the command line (`--bucketbanish.region=...`) or as an env var
(`BUCKETBANISH_REGION=...`).

| Property | Meaning |
|---|---|
| `bucketbanish.endpoint` | S3-compatible endpoint for non-AWS providers (Wasabi, Backblaze B2, DigitalOcean, R2, MinIO...). Leave empty for AWS. Console links are AWS-only |
| `bucketbanish.region` | Region (default: from AWS config/env, else `us-east-1`) |
| `bucketbanish.path-style` | Force path-style addressing (on automatically when an endpoint is set) |
| `bucketbanish.access-key-id` / `bucketbanish.secret-access-key` | Credentials for this connection. Must be set together |
| `bucketbanish.session-token` | Only for temporary credentials |
| `bucketbanish.profile` | Named profile from `~/.aws/credentials` (used when no keys are set) |
| `bucketbanish.local-root` | Read from a local folder instead of S3 |
| `bucketbanish.threads` | Parallel downloads per scan (default 16) |
| `server.port` | HTTP port (default 8080) |

Permissions needed (all read-only): `s3:ListBucket`, `s3:GetObject`, and optionally `s3:GetBucketLocation`
for console links.

If a scan fails with a connection problem (unknown key, wrong secret, wrong region, unreachable endpoint,
access denied), the UI says which setting to fix.

## Using it

1. Enter one or more `s3://bucket[/prefix]` lines and click **Scan**. Options control the burst gap, the
   similarity threshold, the minimum size, SHA-256 verification and cross-camera grouping.
2. Review the **Bursts**, **Same size** and **Same filename** tabs. **Compare side by side** opens a larger
   view where rows that differ between the photos (bucket, folder, size, capture time, ETag...) are highlighted.
3. Tick **Banish** on photos you want gone. "Mark all but largest/first" helps for big groups.
4. On the **Banish list** tab, click **Build removal report**. Follow the console links, or download the CSV
   checklist, and delete the files in your S3 console.

## API

The UI uses a small JSON API, and you can call it directly:

| Endpoint | Purpose |
|---|---|
| `POST /api/scans` `{targets, gapSeconds, threshold, minSize, visual, verify, anyCamera, ignoreCase}` | Start a scan and get back `{id}` |
| `GET /api/scans/{id}` | Progress, then `result` (bursts, sizeGroups, nameGroups, stats) |
| `GET /api/thumbnail?uri=s3://b/k&size=320` | Upright JPEG preview (scanned photos only) |
| `POST /api/report` `{uris:[...]}` | Removal report with current details and console links |
| `POST /api/report.csv` `{uris:[...]}` | The same report as a CSV checklist |
| `GET /api/info` | Store description and whether console links are available |

## How it works

| Step | Approach |
|---|---|
| Listing | `ListObjectsV2` (paginated), filtered to photo extensions |
| Capture date | Range-GET the first 256 KiB, parse with [metadata-extractor](https://github.com/drewnoakes/metadata-extractor); fall back to the full object if no date is found |
| Bursts | Sort by capture time per camera, split where the gap exceeds the burst gap |
| Similarity | Download each burst member, decode with subsampling, 64-bit dHash, Hamming distance ≤ threshold (0 means near-identical, over ~20 means a different picture) |
| Same size | Group by byte size. Matching single-part ETags mean identical content, differing ones mean different content (hidden), and multipart ETags (`...-N`) need SHA-256 verification |
| Previews | Server-side thumbnails (EXIF orientation applied), cached in memory |

Code layout: `analysis/` (EXIF, hashing, grouping), `store/` (S3 and local-folder access, read-only),
`scan/` (background scan jobs), `report/` (removal report), `web/` (REST API, thumbnails),
`resources/static/` (the plain-JavaScript UI styled with [Pico CSS](https://picocss.com) 2.0.6, stored in the project under `static/vendor/` so it works offline, with no build step).

## Known MVP limitations

- **HEIC/RAW** dates are read, but Java can't decode these formats, so there's no preview and bursts are grouped by
  time only. The UI marks them "Time only".
- EXIF time is the camera's local time with no timezone.
- Scan results and marks are kept in memory and are lost on restart. There's no login, so keep it on localhost.
- Visual comparison, previews and SHA-256 verification download whole objects, which costs S3 GET/egress.
- On versioned buckets, deleting in the console adds a delete marker. Old versions remain until removed.
