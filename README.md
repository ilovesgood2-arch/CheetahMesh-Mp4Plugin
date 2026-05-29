# Mp4Plugin — CheetahMesh

Android Godot plugin يحوّل JPG frames لـ MP4 باستخدام `MediaCodec` و `MediaMuxer` المدمجين في Android — بدون ffmpeg.

---

## الملفات المطلوبة في مشروع Godot

```
android/
└── plugins/
    ├── Mp4Plugin.aar     ← من الـ Releases
    └── Mp4Plugin.gdap
```

### Mp4Plugin.gdap
```ini
[config]
name="Mp4Plugin"
binary_type="local"
binary="Mp4Plugin.aar"

[dependencies]
custom_maven_repos=[]
```

---

## تفعيل الـ Plugin

في Godot:
**Project → Export → Android → Plugins → ✅ Mp4Plugin**

---

## الاستخدام من GDScript

```gdscript
if Engine.has_singleton("Mp4Plugin"):
    var plugin = Engine.get_singleton("Mp4Plugin")
    var result = plugin.convertFramesToMp4(
        "/data/user/0/.../mp4_frames/",  # مجلد فيه frame_00000.jpg ...
        "/storage/emulated/0/Download/CheetahMesh/output.mp4",
        24,    # fps
        1280,  # width
        720,   # height
        23     # crf: 18=high, 23=medium, 32=small
    )
    if result.begins_with("OK:"):
        print("MP4 saved: ", result.substr(3))
    else:
        print("Error: ", result)
```

---

## Build محلياً

```bash
./gradlew :android_plugin:assembleRelease
# الـ AAR في: android_plugin/build/outputs/aar/
```

---

## متطلبات

- Android API 21+
- Godot 4.x
