# onx-apk-builder

Standalone builder that turns a membersite URL into an Android WebView wrapper APK.

Extracted from the ONX / DindaPay "Build APK" flow so builds can run on a dedicated
worker (or Docker) instead of the Rails app server.

## Pipeline stages

1. Open membersite URL
2. Fetch brand assets (logo + favicon from CDN)
3. Generate icon mipmaps + splash screen
4. Gradle `assembleDebug`
5. Emit APK path (JSON)

Each stage prints a JSON line:

```json
{"event":"stage","stage":"fetch_assets","label":"Fetch brand assets (logo + favicon)","progress":35,"message":"..."}
```

Final success:

```json
{"event":"done","apk_path":"out/com.onx.membersite.dby.apk","app_name":"DEMOBOY","package_name":"com.onx.membersite.dby","membersite_url":"https://dbymo.online/"}
```

## Requirements (host)

- Ruby 3.2+
- JDK 17+
- Android SDK (`ANDROID_HOME` / `ANDROID_SDK_ROOT`) with `platforms;android-35` and build-tools
- Optional: libvips or ImageMagick for nicer icon resize

## CLI

```bash
bundle install

export JAVA_HOME=...
export ANDROID_HOME=...

bin/build-apk \
  --url https://dbymo.online/ \
  --name DEMOBOY \
  --wlb DBY \
  --out ./out
```

Options:

| Flag | Description |
|------|-------------|
| `--url` | Membersite URL (required) |
| `--name` | App label |
| `--wlb` | WLB slug for default `applicationId` |
| `--package` | Override `applicationId` |
| `--out` | Output directory (default `./out`) |

## Docker

```bash
docker build -t onx-apk-builder .

docker run --rm -v "$PWD/out:/app/out" onx-apk-builder \
  --url https://dbymo.online/ \
  --name DEMOBOY \
  --wlb DBY \
  --out /app/out
```

## Integration with DindaPay / ONX

Keep Rails UI + `WebsiteApkBuild` job in DindaPay. The job should shell out / HTTP-call
this builder on a worker that has the Android toolchain, then attach the resulting APK
for download.

Example:

```ruby
stdout, status = Open3.capture2e(
  { 'ANDROID_HOME' => ENV['ANDROID_HOME'], 'JAVA_HOME' => ENV['JAVA_HOME'] },
  'bin/build-apk', '--url', url, '--name', name, '--wlb', wlb, '--out', out_dir,
  chdir: '/opt/onx-apk-builder'
)
```

Parse `event=stage` lines for progress UI; on `event=done` store `apk_path`.

## License

Private / internal use.
