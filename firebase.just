import 'common.just'

# list this module's recipes
default:
    @just --list firebase

# Registers app.id + .debug + .web (creating the Android apps if missing) and writes their merged
# config to the per-app-id path the build resolves. Requires `firebase login` (uses an installed
# `firebase`, else `npx firebase-tools`). project= defaults to the project id in any existing
# keystore/google-services.*.json. Example: `just firebase google-services` or `just firebase google-services nbrs-243915`.
# Fetch Firebase config for the current app.id -> keystore/google-services.<app.id>.json.
google-services project="":
    #!/usr/bin/env bash
    set -euo pipefail
    app_id=$(grep -E '^app\.id=' local.properties 2>/dev/null | cut -d= -f2-)
    [ -n "$app_id" ] || { echo "set app.id in local.properties first" >&2; exit 1; }
    project="{{project}}"
    if [ -z "$project" ]; then
        project=$(python3 -c "import json,glob,sys; f=sorted(glob.glob('keystore/google-services.*.json')); sys.exit('no keystore/google-services.*.json to infer project; pass project=<id>') if not f else print(json.load(open(f[0]))['project_info']['project_id'])")
    fi
    out="keystore/google-services.${app_id}.json"
    fb=$(command -v firebase || true); [ -n "$fb" ] || fb="npx -y firebase-tools"
    echo "project: $project   app.id: $app_id   ->  $out   (using: $fb)"
    tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
    apps=$($fb apps:list ANDROID --project "$project" --json)
    files=()
    for suffix in "" .debug .web; do
        pkg="${app_id}${suffix}"
        aid=$(printf '%s' "$apps" | python3 -c "import json,sys; d=json.load(sys.stdin).get('result',[]); print(next((a['appId'] for a in d if (a.get('packageName') or a.get('namespace'))=='$pkg'),''))")
        if [ -z "$aid" ]; then
            echo "creating Android app $pkg ..."
            aid=$($fb apps:create ANDROID "$pkg" --package-name "$pkg" --project "$project" --json | python3 -c "import json,sys; print(json.load(sys.stdin)['result']['appId'])")
        else
            echo "found    $pkg  ($aid)"
        fi
        $fb apps:sdkconfig ANDROID "$aid" --project "$project" > "$tmp/$pkg.json"
        files+=("$tmp/$pkg.json")
    done
    python3 - "$out" "${files[@]}" <<'PY'
    import json, sys
    out, files = sys.argv[1], sys.argv[2:]
    def load(f):
        raw = open(f).read()
        return json.loads(raw[raw.index("{"):raw.rindex("}") + 1])
    base = load(files[0])
    clients = {}
    for f in files:
        for c in load(f).get("client", []):
            clients[c["client_info"]["android_client_info"]["package_name"]] = c
    base["client"] = list(clients.values())
    json.dump(base, open(out, "w"), indent=2)
    print("wrote %s with %d client(s): %s" % (out, len(clients), ", ".join(sorted(clients))))
    PY
    echo "done — run: just build aab-arm64"
