# Third-party notices

This Android port follows the license of [tvbox-silicon / Yingxia](https://github.com/jo32/tvbox-silicon): **GPL-3.0-only**. See `LICENSE`. Source baseline is recorded in `docs/SOURCE_REVISION`.

| Component | Origin | License |
| --- | --- | --- |
| Yingxia design, icon, script hosts and site assets | tvbox-silicon at the recorded revision | GPL-3.0-only; embedded file notices remain applicable |
| QuickJS-ng v0.17.0 | https://github.com/quickjs-ng/quickjs | MIT; see `vendor/QuickJS/LICENSE` and `UPSTREAM.md` |
| TVScript bridge | tvbox-silicon, with Android threading/JNI adaptations | GPL-3.0-only |
| ItaloDance radio behavior and song timing | User-authorized port of the same author's Mp4Hub ARM32 TV implementation; rewritten for this Java UI | Included under this project's GPL-3.0-only |
| CatVod host net/utils APIs | Original project's JavaHost adaptations of FongMi/TV, commit 4512b4c | GPL-3.0; source headers retained |
| AndroidX Media3 1.4.1 | https://github.com/androidx/media | Apache-2.0 |
| AndroidX annotations | https://android.googlesource.com/platform/frameworks/support | Apache-2.0 |
| desugar_jdk_libs_nio 2.1.2 | https://github.com/google/desugar_jdk_libs | GPL-2.0 with Classpath Exception; see upstream notices |
| OkHttp 4.12.0 / Okio | https://github.com/square/okhttp | Apache-2.0 |
| Gson 2.11.0 | https://github.com/google/gson | Apache-2.0 |
| jsoup 1.18.1 | https://github.com/jhy/jsoup | MIT |
| ZXing 3.5.3 | https://github.com/zxing/zxing | Apache-2.0 |
| JUnit 4.13.2 (tests only) | https://github.com/junit-team/junit4 | EPL-1.0 |
| JSON-java (unit tests only) | https://github.com/stleary/JSON-java | Public domain |

Script assets may contain additional upstream notices. They have been copied with the files. The original website's demo artwork is served only by the local screenshot fixture server; attribution is in `tvbox-silicon/website/public/film/SOURCES.txt`. It is not packaged into the application.

The app includes the user-requested public FTY subscription snapshot from https://github.com/qist/tvbox and the public ItaloSound radio endpoints. It does not include private user accounts or login sessions. Source and station availability is controlled by their operators.
