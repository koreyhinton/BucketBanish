# BucketBanish

A Linux command-line tool that finds photos in S3 you probably don't need:

- **Burst shots**: photos from the same camera taken within a few seconds of each other that also *look* alike
  (perceptual dHash). You review each set and pick which ones to drop.
- **Same byte size**, both within one bucket and across buckets. Groups are labelled with the S3 ETag (MD5)
  or, with `--verify`, a SHA-256.
- **Same filename in more than one bucket**.

BucketBanish is **read-only and never deletes anything**. `banish` prints a report of where each chosen file lives:
bucket, region, folder, filename, size, upload date, ETag, and an AWS console link. You then delete the files
yourself in your S3 provider's web console.

## Build

Requires Java 21.

```bash
./gradlew installDist          # -> build/install/bucketbanish/bin/bucketbanish
./gradlew test
alias bucketbanish=$PWD/build/install/bucketbanish/bin/bucketbanish
```

## Credentials

It uses the standard AWS SDK credential chain (env vars, `~/.aws/credentials`, SSO, instance role).
Options on every command: `--profile NAME`, `--region REGION`, `--endpoint URL` (for any S3-compatible service).

IAM permissions needed (all read-only): `s3:ListBucket`, `s3:GetObject`, and `s3:GetBucketLocation`.
The last one is optional; without it, `banish` can't build console links.

## Usage

```bash
# Metadata: original capture date (EXIF DateTimeOriginal + sub-seconds), camera, dimensions
bucketbanish scan s3://family-photos/2024/
bucketbanish scan --csv s3://family-photos > photos.csv

# Burst detection (report only)
bucketbanish bursts s3://family-photos --gap 2 --threshold 10

# Review interactively and write a plan file
bucketbanish bursts -i s3://family-photos --plan banish-plan.txt
#   Enter = keep all | 2,4 or 2-4 = drop those | k3 = keep only #3 | s = keep largest | q = stop

# Same sizes / same filenames across buckets
bucketbanish dupes s3://family-photos s3://backup-photos
bucketbanish dupes --verify --plan dupes-plan.txt s3://family-photos s3://backup-photos

# Report where to find each planned file, then delete them in the S3 web console
bucketbanish banish banish-plan.txt
bucketbanish banish --csv banish-plan.txt > checklist.csv   # spreadsheet checklist
```

Plan files are plain text, one `s3://bucket/key` per line, and `#` starts a comment, so you can edit them by hand.

### How it works

| Step | Approach |
|---|---|
| Listing | `ListObjectsV2` (paginated), filtered to photo extensions |
| Capture date | Range-GET the first 256 KiB, parse with [metadata-extractor](https://github.com/drewnoakes/metadata-extractor); fall back to the full object if no date is found |
| Bursts | Sort by capture time per camera, split where the gap exceeds `--gap` seconds |
| Similarity | Download each burst member, decode with subsampling, 64-bit dHash, Hamming distance ≤ `--threshold` (0 means near-identical, over ~20 means a different picture) |
| Same size | Group by byte size. Matching single-part ETags mean identical content, differing ones mean different content, and multipart ETags (`...-N`) can't be compared, so use `--verify` |

## Known MVP limitations

- **HEIC/RAW** dates are read, but Java's ImageIO can't decode these formats, so their bursts are grouped by time only.
  The output marks them "(not compared visually)".
  Fix: add a HEIC decoder, or use the embedded JPEG preview.
- EXIF time is the camera's local time with no timezone. Bursts are fine, but cross-camera comparisons may be off.
- No local cache, so every run re-downloads. Hashing downloads full objects, which costs S3 GET/egress.
- Console links are AWS-only. With `--endpoint` (another provider), the report still lists bucket, folder and filename.
- On versioned buckets, deleting in the console adds a delete marker. Old versions remain until lifecycle rules remove them.
