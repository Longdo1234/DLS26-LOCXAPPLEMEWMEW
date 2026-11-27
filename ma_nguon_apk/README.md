# Dự Án DLS26 / DLS25 Auto Offline Pro — Tài Liệu Bàn Giao Toàn Diện (Dành Cho Người Dùng & AI Phát Triển Tiếp)

Tài liệu này tổng hợp **toàn bộ kiến trúc, thuật toán, cấu trúc tệp, cơ chế bảo mật và quy trình biên dịch** của dự án tự động hóa **Dream League Soccer (DLS25 / DLS26)** chế độ đá Offline với máy.

> **Mẹo dành cho Người nhận dự án:** Nếu sau này bạn muốn thêm tính năng, chỉnh thông số lối đá, đổi giao diện hoặc nâng cấp sang bản DLS26 / DLS27, bạn chỉ cần mở thư mục giải nén này trong công cụ lập trình AI bất kỳ (Antigravity, Cursor, Claude Code, Codex...) và nhắn:
> *"Hãy đọc file `README.md` ở thư mục gốc để nắm toàn bộ kiến trúc dự án rồi giúp tôi thực hiện thay đổi sau: [...]"*

---

## 1. Tổng Quan Những Gì Có Trong Gói Bàn Giao Này

Gói dự án bao gồm **2 phiên bản hoàn chỉnh** dùng chung triết lý AI nhưng tối ưu riêng cho từng nền tảng:

```text
DLS26_Auto_Full_Project.zip
├── README.md                        # Tài liệu tổng hợp kiến trúc & hướng dẫn phát triển tiếp (file này)
├── DLS_Auto_AppleMewMew.apk         # Bản APK hoàn chỉnh mới nhất cài ngay lên điện thoại Android thật (Android 8.0 -> 16)
│
├── dls26-auto/                      # [NỀN TẢNG 1] Mã nguồn bản PC điều khiển Giả lập (BlueStacks / LDPlayer / MuMu / Nox)
│   ├── 1_Mo_Bang_Dieu_Khien_Cat_Mau.bat   # Mở Web Studio trực quan (http://127.0.0.1:4726) chụp & kéo chuột cắt nút mẫu
│   ├── 2_Chay_Thu_Khong_Bam_DryRun.bat    # Chạy nhận diện thử liên tục (không bấm thật vào game)
│   ├── 3_Bat_Dau_Treo_DLS26.bat           # Bắt đầu treo 100 trận (đã tích hợp chống đóng băng QuickEdit của CMD)
│   ├── cli.js                             # Giao diện dòng lệnh (doctor, studio, capture, dry-run, start, preset)
│   ├── config.default.json                # Cấu hình gốc (tọa độ chuẩn 1280x720, ROI, ngưỡng khớp, 100 trận)
│   ├── android/dls/TouchServer.java       # Mã nguồn daemon cảm ứng đa điểm 2 ngón 60Hz chạy trong Android qua app_process
│   ├── bin/dls-touch-server.jar           # Bản biên dịch DEX/JAR của TouchServer (nạp tự động qua ADB cổng TCP 19726)
│   ├── presets/dls25-mac-dinh/            # Bộ Preset lưu sẵn cấu hình + 12 ảnh mẫu chuẩn DLS25
│   ├── templates/                         # Thư mục chứa 12 ảnh nút mẫu (.png) đang kích hoạt
│   ├── logs/brain-weights.json            # Trọng số AI học tăng cường (Contextual Bandit Q-Table) đã huấn luyện sẵn
│   ├── src/                               # Toàn bộ module lõi Node.js thuần (0 phụ thuộc ngoài, không cần npm install!)
│   │   ├── adb.js                         # Kết nối ADB đa ổ đĩa (C..F), đọc màn hình & tự hồi phục kết nối
│   │   ├── png.js                         # Giải mã/mã hóa PNG thuần zlib, co giãn bilinear, cắt ảnh & vẽ khung debug
│   │   ├── matcher.js                     # Nhận diện mẫu ZNCC ảnh tích phân + lọc màu RGB O(1) + nhận diện mặt cỏ
│   │   ├── touch-gamepad.js               # Client TCP 2 ngón kết nối tới dls-touch-server.jar (Joystick + lực A/B/C + Skill)
│   │   ├── tactics.js                     # Lõi AI Chiến thuật V3 (Lối đá người thật Tiki-Taka, đỡ bước 1, kèm người B)
│   │   ├── learner.js                     # Bộ học tăng cường theo 11 vùng sân (tự lưu sau mỗi chuỗi pha bóng)
│   │   ├── bot.js                         # Máy trạng thái trận đấu 100 trận, quét nhanh trong trận & tự thoát menu phụ
│   │   ├── presets.js                     # Lưu / nạp các bộ cài đặt Preset
│   │   ├── studio-server.js               # Máy chủ HTTP cục bộ phục vụ giao diện Web Studio
│   │   └── ui/                            # Giao diện Web Studio (index.html, styles.css, app.js)
│   └── test/self-test.js                  # Bộ kiểm thử tự động toàn diện cho bản PC
│
└── dls-auto-apk/                    # [NỀN TẢNG 2] Mã nguồn ứng dụng Android độc lập (không cần PC, không cần Root)
    ├── AndroidManifest.xml                # Khai báo quyền Overlay, MediaProjection ForegroundService, AccessibilityService
    ├── build-apk.ps1                      # Script tự động biên dịch -> đóng gói -> zipalign -> ký số V2/V3 -> chạy test
    ├── keystore/applemewmew.jks           # Keystore ký số chính thức (giữ nguyên để bản build sau cài đè không mất dữ liệu)
    ├── assets/
    │   ├── default_config.json            # Cấu hình neo góc đa màn hình Android (LEFT / RIGHT / CENTER, 100 trận)
    │   ├── brain_weights.json             # Trọng số AI đã huấn luyện nạp sẵn cho APK
    │   └── templates/                     # 12 ảnh mẫu chuẩn đóng gói không nén (STORED) chuẩn POSIX '/'
    ├── res/                               # Icon ứng dụng, strings.xml (câu chào bản quyền), cấu hình Trợ năng
    ├── src/com/applemewmew/dlsauto/
    │   ├── security/CopyrightGuard.java   # Bảo vệ toàn vẹn bản quyền đa lớp (SHA-256 chuỗi chữ + chứng chỉ ký + ràng buộc toán học)
    │   ├── model/BotConfig.java           # Quản lý cấu hình trên điện thoại & xuất/nhập gói mẫu (.dlsauto) qua Clipboard
    │   ├── vision/RgbaImage.java          # Đọc bộ đệm GPU ImageReader tốc độ cao (xử lý rowStride phần cứng)
    │   ├── vision/ScreenGeometry.java     # Ánh xạ đa tỷ lệ màn hình điện thoại Android (16:9, 18:9, 19.5:9, 20:9, 21:9 + tai thỏ)
    │   ├── vision/ZnccMatcher.java        # Nhận diện mẫu ZNCC ảnh tích phân + lọc màu RGB tốc độ cao trên Java/ART
    │   ├── ai/TacticsEngine.java          # Lõi AI Chiến thuật V3.1 đa tỷ lệ màn hình (Radar tự căn giữa đáy động, hướng bắn đúng tỉ lệ radar 189×127)
    │   ├── ai/BrainLearner.java           # Bộ học tăng cường lưu trữ trên bộ nhớ trong của điện thoại
    │   ├── ai/MatchBotEngine.java         # Máy trạng thái 100 trận + tự động bắt dính tâm nút A/B/C theo ký tự trên sân
    │   ├── ai/TouchDispatcher.java        # Giao diện trừu tượng điều khiển cảm ứng đa điểm
    │   ├── service/AutoAccessibilityService.java # Điều khiển 2 ngón đồng thời qua GestureDescription + continueStroke (cần + nút B giữ liền mạch khi áp sát) + chụp dự phòng Android 11+
    │   ├── service/ScreenCaptureService.java     # Dịch vụ thu màn hình MediaProjection (dùng VirtualDisplay.resize chuẩn Android 14-16)
    │   ├── ui/BrandIntroView.java         # Màn hình chào hoạt ảnh 3 giây ("Bản quyền thuộc về Apple Mew Mew / Mọi tính năng đều miễn phí")
    │   ├── ui/FloatingOverlayManager.java # Menu Nổi trong game + Kéo 4 vòng căn tâm nút + Kéo ngón tay cắt mẫu + Tối màn 1% treo đêm
    │   └── ui/MainActivity.java           # Giao diện 3 Tab tiếng Việt: [Trang Chủ], [Cài Đặt & Mẫu], [Hướng Dẫn Sử Dụng]
    └── test/ApkEngineSelfTest.java        # Bộ kiểm thử tự động JVM kiểm tra chống sửa bản quyền, khung 20:9 & AI chiến thuật
```

---

## 2. Chi Tiết Các Thành Tựu Kỹ Thuật Đã Xây Dựng (Dành Cho AI Đọc Hiểu Sâu)

### 2.1. Nhận diện hình ảnh ZNCC + Cổng lọc màu RGB O(1)
- Cả bản PC (`dls26-auto/src/matcher.js`) và bản Android (`dls-auto-apk/.../ZnccMatcher.java`) đều tự cài đặt thuật toán **Zero-Mean Normalized Cross-Correlation (`TM_CCOEFF_NORMED`)** trên **bảng tích phân (`Integral Image`: `sum`, `sumSq`, `sumR`, `sumG`, `sumB`)** mà không phụ thuộc OpenCV.
- **Tối ưu tốc độ & chống nhận nhầm**: Trước khi tính tích vô hướng độ chói của từng cửa sổ, thuật toán kiểm tra độ lệch màu trung bình `windowColorDiff` trong thời gian **`O(1)`**, loại bỏ ~96% vị trí sai màu chỉ trong vài nano-giây (giúp phân biệt tuyệt đối nút xanh lá `PLAY` với nút đỏ/vàng có cùng kích thước).

### 2.2. Xử lý Khác biệt Khung hình Điện thoại Android (`16:9` → `21:9`) so với PC (`16:9`)
- **Trên bản PC (`dls26-auto`)**: Giả lập để tỷ lệ `16:9` (`1280×720`, `1600×900`, `1920×1080`), ảnh chụp `screencap -p` được chuẩn hóa thẳng về `1280×720`. *(Lưu ý quan trọng trên BlueStacks 5: bắt buộc giữ cờ `-p` trong `screencap -p` để kích hoạt hook đọc lớp OpenGL 3D của BlueStacks).*
- **Trên bản APK Điện thoại (`dls-auto-apk`)**: Điện thoại Android thực tế có màn hình dài (`19.5:9`, `20:9`, `21:9`) và camera đục lỗ (`DisplayCutout`). Nếu ép co về `1280×720` sẽ làm méo hình tròn của nút bấm. Do đó `ScreenGeometry.java` giữ nguyên tỷ lệ gốc ở chiều cao chuẩn `720p` (`normWidth × 720`, ví dụ `2400×1080` → `1600×720`) và áp dụng **Neo Góc (`Anchor-based ROI`)**:
  - `LEFT`: Neo mép trái + bù lề tai thỏ (`in_match_hud`, `replay_exit`, `nav_back`, `home_career`, `start_new_division`, `lobby_play`, `Joystick`).
  - `RIGHT`: Neo mép phải + mở rộng vùng quét bao phủ cả bố cục khung an toàn (`pre_match_play`, `post_match_continue`, `btn_a_glyph`, `btn_b_glyph`, cụm nút `A/B/C`).
  - `CENTER`: Tự động dịch vào chính giữa màn hình thực tế (`half_time_resume`, `pause_resume`, và hộp **Radar 2D** rộng `189×127` luôn tự khóa 4 cạnh viền ở tâm đáy `(normWidth - 189)/2`).
  - **Tự bắt dính nút `A/B/C`**: Ngay khi vào trận, `MatchBotEngine.java` dùng vị trí khớp thực tế của `btn_a_glyph` và `btn_b_glyph` để tự căn chỉnh tâm chạm `A`, `B`, `C` chuẩn xác tới từng pixel cho mọi dòng máy (hoặc người dùng tự kéo 4 vòng tròn qua công cụ `🎯 Đặt tâm nút` trên Menu Nổi).

### 2.3. Danh sách 12 Ảnh Mẫu Đóng Gói Sẵn & Vai Trò Trong Máy Trạng Thái
1. `online_live_guard.png` *(Tùy chọn an toàn)*: Phát hiện chế độ đá Online (*Dream League Live*) → Lập tức khóa thao tác và dừng bot.
2. `replay_exit.png`: Nút `<` góc trên trái để thoát ngay khỏi màn hình xem lại bàn thắng (Replay).
3. `half_time_resume.png`: Nhận diện đồng hồ `45:00` giữa trên khi nghỉ giữa hiệp → Cộng `tapOffset (dx: 259, dy: 507)` bấm vào nút `RESUME` sang Hiệp 2 & tự động đảo chiều khung thành tấn công.
4. `pause_resume.png`: Nhận diện màn hình tạm dừng (`Pause`) → Cộng `tapOffset (dx: 190, dy: 0)` bấm tiếp tục đá.
5. `post_match_continue.png`: Nút `CONTINUE` góc dưới phải khi kết thúc trận → Tăng biến đếm số trận hoàn thành (`1..100`) với nhịp chờ `2200ms` tránh bấm đúp nhầm vào thẻ sân vận động.
6. `pre_match_play.png`: Nút `PLAY` xanh lá góc dưới phải ở màn chọn áo đấu trước trận (ngưỡng `0.72` chống chói hiệu ứng vệt sáng).
7. `lobby_play.png`: Góc tĩnh thẻ `PLAY NOW` ở sảnh Career + `tapOffset (dx: 220, dy: 120)`.
8. `home_career.png`: Thẻ `CAREER` trên màn hình chính Home + `tapOffset (dx: 180, dy: 120)`.
9. `start_new_division.png`: Thẻ `START NEW DIVISION` khi hoàn thành đủ 15 trận của 1 mùa giải → Tự động khởi tạo mùa mới xuyên suốt chuỗi **100 trận**.
10. `in_match_hud.png`: Nút tạm dừng `||` góc trên trái xác nhận bóng đang lăn trên sân.
11. `nav_back.png`: Mũi tên cam `←` góc trên trái → Nếu vô tình lạc vào menu phụ (Sân vận động, Đội hình...) trong 4 nhịp liên tiếp (`~1.8s`), bot tự bấm `←` lùi về sảnh chính.
12. `btn_a_glyph.png` & `btn_b_glyph.png`: Chữ `A` và `B` tâm nút điều khiển → Vừa giúp nhận diện tình huống **bóng chết góc nhìn thứ nhất** (khi Radar 2D ẩn nhưng nút sút vẫn hiện → giữ `C` lực `405ms` phát bóng lên ngay không chờ hết giờ), vừa giúp tự bắt dính tọa độ nút trên điện thoại.

### 2.4. Lõi AI Chiến Thuật Lối Đá Người Thật (V3 Human-Like Tiki-Taka & Disciplined Defense)
Nằm tại `dls26-auto/src/tactics.js` (PC) và `dls-auto-apk/.../TacticsEngine.java` (Android):
- **Loại bỏ nhiễu Radar & Khóa hướng khung thành chuẩn 100%**:
  - Hàm `isPaintedRadarPitchLine` lọc sạch vạch giữa sân (`relX ≈ 0.50`), vòng tròn trung tâm (`R ≈ 19.5px`), đường biên và vạch 16m50.
  - Tính hướng tấn công (`+1` sang phải, `-1` sang trái) bằng **phân vị đội hình `Q25 / Q50 / Q75` loại trừ vùng thủ môn (`0.09..0.91`)**, nên áo thủ môn khác màu không bao giờ làm đảo ngược hướng tấn công. Sang Hiệp 2 tự động lật dấu `-prevDir`.
- **Nhịp tấn công điềm tĩnh như người thật**:
  - **Đệm bước một (`ballInFlightUntil`)**: Sau mỗi đường chuyền, trong `~0.8s – 1.1s` khi bóng đang lăn tới đồng đội, AI **không bấm chồng nút chuyền/sút/skill**, chỉ nghiêng nhẹ Joystick về hướng trống để cầu thủ khống chế bước một êm ái.
  - **Dẫn bóng & Đập nhả sệt Tiki-Taka**: Khi trước mặt thoáng (`>= 15.5%`), ưu tiên giữ Joystick dẫn bóng mượt `2–3` nhịp thu hút hậu vệ rồi mới **chuyền sệt `B` (`105–220ms`)** hoặc **chọc khe sệt `B` đón đà** (`15–32m`). Cấm chuyền lùi bên sân đối phương (chỉ cho phép luân chuyển ngang ngắn mở góc khi bị 2 hậu vệ vây kín đường tiến).
  - **Kỷ luật dứt điểm (Không lạm dụng nút `A`)**: Không bao giờ sút bừa ngoài vòng cấm (`< 0.77`). Khi vào trung lộ vòng cấm (`>= 0.77`), khóa chống nhồi phím `1600ms` và dứt điểm 1 phát chuẩn xác ngược hướng đứng của thủ môn: đối mặt/góc chéo dùng **sửa lòng sệt `B` (`185ms`)**, chính diện 15–18m dùng **sút chìm `A` lực vừa (`142ms`)** để bóng không vọt xà.
- **Phòng ngự kỷ luật (`0` lần xoạc bóng `A`)**:
  - Hoàn toàn không dùng nút xoạc `A` (`SLIDE TACKLE`) để tránh hậu vệ nằm sân hoặc phạm lỗi.
  - Phối hợp **đổi người `C` điềm tĩnh (`1350ms`)** sang hậu vệ đứng chắn trước mặt bóng.
  - **Áp sát (V3.1)**: khi cách người cầm bóng `<= PRESS_DIST (0.22)` → **kéo cần thẳng vào cầu thủ đối thủ cầm bóng + GIỮ `B` liên tục** (`holdDefendContain`, 2 ngón nối nét bằng `continueStroke`, không nhấc `B` giữa các nhịp). Xa hơn thì chỉ kéo cần đuổi theo (chạy cắt đường), chưa giữ `B`.

### 2.4b. Bản vá V3.1 — 5 lỗi lối đá đã sửa (đọc kỹ trước khi chỉnh tiếp `TacticsEngine.java`)

| # | Lỗi người dùng báo | Nguyên nhân gốc trong mã | Cách sửa |
|---|---|---|---|
| 1 | **Áp sát bot không giữ `B`, không kéo cần vào người cầm bóng** | `holdDefendContain` chỉ bấm `B` một lần `405ms` rồi nhả; chu kỳ bot dài hơn nên `B` bị nhả `~100–200ms` mỗi nhịp. Hướng cần thì nhắm vào điểm *lùi về phía khung thành nhà* chứ không phải vào người cầm bóng. | `AutoAccessibilityService.holdDefendContain` nay giữ cả cần lẫn `B` bằng `continueStroke` (slice `620ms`, nối nét giữa các nhịp, `releaseAll` nhấc cả 2 ngón). `TacticsEngine` CASE 4: `<= 0.22` → cần chỉ thẳng người cầm bóng + giữ `B`; xa hơn → `holdJoystick` đuổi theo. |
| 2 | **Tạt bóng quá nhẹ, không ai đánh đầu** | Tạt cố định `C 205ms` (yếu hơn cả phát bóng GK `390ms`), nhắm vào một điểm cố định, và sau tạt bot dùng nhầm cú sút `B` mặt đất. | Lực tạt `CROSS_BASE_MS + dist × CROSS_SLOPE_MS` kẹp `[290, 400]ms`; nhắm đồng đội nhiều khoảng trống nhất trong vòng cấm (không có thì chấm phạt đền). Sau tạt mở cửa sổ đánh đầu: khi bóng sắp tới và `me` đang ở trong vòng cấm → bấm `A` **một lần** `HEADER_HOLD_MS = 130ms` về phía khung thành (cách nhau `>= 900ms`, không nhồi). Trong lúc bóng bay, cần dẫn cầu thủ vào vùng rơi thay vì chạy lung tung. |
| 3 | **Chuyền ngáo** | (a) Hướng chuyền/sút/dẫn được chuẩn hoá trong không gian `0..1` nhưng radar là `189×127 px` → chuyền chéo bị lệch tới `~11°` (ví dụ đúng `30°` nhưng bot kéo `41°`). (b) Cho chuyền xa tới `0.52` trong khi đường cong lực chỉ chỉnh tới `~0.35` → bóng chết giữa đường. (c) Mất dấu `me` thì bấm `B` bừa mỗi nhịp thứ 3. | Hàm mới `aimUnit(dx, dy)` tính hướng theo **pixel radar**; mọi đường chuyền/sút/dẫn/phòng ngự dùng nó. `MAX_PASS_RANGE = 0.42`, trần lực chuyền `240ms`. Mất dấu `me`: chỉ chuyền gỡ kẹt tối đa 1 lần mỗi 5 nhịp (sau `>= 5` nhịp). |
| 4 | **Đang công bỗng chuyền về** | (a) "Dọn cỗ" (`sweaty_cutback_b`, utility `0.95`) cho phép từ mọi vị trí `progress >= 0.75` kể cả chuyền lùi khi chưa bị kèm. (b) Chuyền hỗ trợ cho lùi `-0.045`. (c) Khám phá ngẫu nhiên 5% có thể bốc trúng "dọn cỗ" bất kỳ lúc nào. | Chuyền lùi kiểu "dọn cỗ" chỉ khi sát đường biên ngang (`progress >= 0.86`) **hoặc** bị chặn (`nearestAhead < 0.13`); chuyền hỗ trợ chỉ còn chuyền ngang (`>= -0.012`); `BrainLearner.selectAction` không bao giờ khám phá ngẫu nhiên vào `sweaty_cutback_b` / `wing_cross_c`. |
| 5 | **Chuyền/ném biên xong cầu thủ dẫn thẳng ra ngoài** | Ném biên không có đồng đội hợp lệ mặc định `uy = -0.20` (đi lên) → đứng biên trên thì ném *ra ngoài*; `evaluateDribble` không có hướng dự phòng khi mọi bước đều chạm biên (rơi về chạy dọc đường biên); mất `me` thì giữ cần ngang. | Ném biên (`isDeadBall` và `me.normY <= 0.10 / >= 0.90`): chỉ chọn đồng đội nằm **vào trong sân** (`evaluateThrowInTarget`), luôn ép thành phần hướng vào trong `>= 0.45`; dẫn bóng không bao giờ chọn bước ra ngoài, phạt nhẹ vùng sát biên `< 0.10`, và có hướng dự phòng quay vào sân. |

**Hằng số chỉnh nhanh** (đầu `TacticsEngine.java`): `CROSS_BASE_MS`, `CROSS_SLOPE_MS`, `CROSS_MIN_MS`, `CROSS_MAX_MS`, `HEADER_HOLD_MS`, `PRESS_DIST`, `MAX_PASS_RANGE`, `EDGE_RESTART_MARGIN`. Nếu tạt vẫn nhẹ/quá mạnh → chỉnh `CROSS_MIN_MS/MAX_MS`; nếu áp sát quá sớm/muộn → chỉnh `PRESS_DIST`.

**Tỉ lệ màn hình (V3.1):** mọi tọa độ chạy trong hệ chuẩn cao cố định `720` và co dãn **đều hai trục** (`vật lý = chuẩn × cao/720`), radar luôn `189×127` ở giữa đáy, nên góc cần điều khiển được giữ nguyên trên mọi máy (đã kiểm tra 14 kích thước từ 16:9 → 21:9 và tablet 16:10 / 4:3: lệch tỉ lệ `<= 0.02%`, lệch góc cần `<= 0.33°`). Đã sửa thêm `ScreenGeometry.widthDeltaVs16x9()` để A/B/C và ô menu căn giữa **dịch sang trái khi màn hình hẹp hơn 16:9** (trước đây tablet 16:10 bị nút A dính mép, 4:3 bị B đè lên A). Phần tablet là suy ra từ quy tắc bám mép phải của bản 16:9, **chưa kiểm tra trên tablet thật**; nút vẫn được tự bắt lại bằng so khớp biểu tượng nếu lệch.

> **Lưu ý cho AI phát triển tiếp:** các thông số lực (`ms`) là suy ra từ mã nguồn + so sánh với các lực đã chỉnh tay sẵn (phát bóng GK `390ms`, phát bóng góc nhìn 1 `405ms`), **chưa được chạy thử trên máy thật/DLS thật**. Sau khi build, hãy xem vài trận và chỉnh các hằng số trên nếu cảm giác chưa chuẩn. `ApkEngineSelfTest` đã có thêm bộ kiểm thử hồi quy (`runTacticalRegression`) cho 5 lỗi trên; `BrainLearner.setEpsilon(0)` dùng để test tất định.

### 2.5. Màn hình Chào 3 Giây & Cơ Chế Khóa Bản Quyền trên APK (`CopyrightGuard.java`)
- Khi mở APK, `BrandIntroView.java` chạy hiệu ứng ánh kim ~3 giây hiển thị 2 dòng trong `res/values/strings.xml`:
  - Dòng 1: `Bản quyền thuộc về Apple Mew Mew`
  - Dòng 2: `Mọi tính năng đều miễn phí`
- `CopyrightGuard.java` kiểm tra đồng thời mã băm `SHA-256` của 2 dòng chữ này (`EXPECTED_TEXT_HASH`) và mã băm `SHA-256` chứng chỉ ký APK từ `keystore/applemewmew.jks` (`EXPECTED_CERT_HASH`), đồng thời cộng trực tiếp `znccBias()` và `coordCipherOffset()` vào phép tính nhận diện & tọa độ chạm.
- **Lưu ý dành cho AI khi chủ dự án muốn đổi câu chữ chào mừng hoặc đổi Keystore hợp lệ**:
  - Nếu chỉnh sửa `copyright_line_1` / `copyright_line_2` trong `dls-auto-apk/res/values/strings.xml` hoặc tạo keystore mới, hãy tính lại `SHA-256("DLS_AMM_V1|" + line1 + "\n" + line2)` (UTF-8) và `SHA-256(certDerBytes)` rồi cập nhật 2 hằng số `EXPECTED_TEXT_HASH` và `EXPECTED_CERT_HASH` trong `dls-auto-apk/src/com/applemewmew/dlsauto/security/CopyrightGuard.java` + chuỗi kiểm thử trong `test/ApkEngineSelfTest.java`.

---

## 3. Hướng Dẫn Sử Dụng & Biên Dịch Lại Khi Phát Triển Tiếp

### A. Sử dụng ngay cho Người dùng cuối
- **Trên điện thoại Android thật**:
  1. Cài file **`DLS_Auto_AppleMewMew.apk`** lên điện thoại.
  2. Mở ứng dụng (chờ 3 giây màn hình chào), đọc tab **`📖 HƯỚNG DẪN`** tích hợp sẵn bên trong ứng dụng, cấp 2 quyền *Nút Nổi (Overlay)* và *Trợ năng (DLS Auto)* *(trên Android 13/14/15 nếu mục Trợ năng bị mờ, bấm nút "Mở Quyền Hạn Chế" ngay trong app → dấu `⋮` góc trên phải → "Cho phép cài đặt bị hạn chế")*.
  3. Bấm **🚀 Bật Menu Nổi** (chọn *Toàn màn hình / Entire Screen*) → mở game DLS → chạm viên thuốc nổi bấm **▶ Chạy Treo**.
- **Trên máy tính Windows (Giả lập BlueStacks)**:
  1. Cài **Node.js LTS** (https://nodejs.org) và bật **ADB** trong *BlueStacks Settings → Advanced*.
  2. Vào thư mục `dls26-auto/`, nhấp đúp **`3_Bat_Dau_Treo_DLS26.bat`**.

### B. Kiểm thử & Biên dịch lại dành cho Lập trình viên / AI
1. **Chạy kiểm thử tự động bản PC (`dls26-auto`)**:
   ```powershell
   cd dls26-auto
   node test/self-test.js
   ```
2. **Biên dịch & ký số lại bản APK Android (`dls-auto-apk`)**:
   Yêu cầu máy Windows có **JDK 17+** và **Android SDK** (`platforms\android-34+` và `build-tools` bản mới). Từ V3.1, `build-apk.ps1` **tự dò JDK/SDK** (tham số `-JdkHome`, `-SdkRoot`, hoặc biến `JAVA_HOME`, `ANDROID_HOME`) và **luôn build từ chính thư mục chứa script** (không còn cố định `D:\DLS25\dls-auto-apk`, tránh vô tình build lại mã cũ). Mở PowerShell trong thư mục dự án rồi chạy:
   ```powershell
   cd <thu_muc_giai_nen>\ma_nguon_apk
   powershell -NoProfile -ExecutionPolicy Bypass -File .\build-apk.ps1
   # nếu không tự tìm thấy JDK/SDK:
   powershell -NoProfile -ExecutionPolicy Bypass -File .\build-apk.ps1 -JdkHome "C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot" -SdkRoot "$env:LOCALAPPDATA\Android\Sdk"
   ```
   Script sẽ tự động chạy `aapt2` → `javac` → `d8` → chuẩn hóa đường dẫn POSIX `/` cho `assets/` → `zipalign` → `apksigner` (V1 + V2 + V3) và chạy toàn bộ bộ kiểm thử `ApkEngineSelfTest` (gồm cả kiểm thử chiến thuật V3.1 và kiểm thử tỉ lệ màn hình). File xuất ra: **`DLS_Auto_AppleMewMew.apk` nằm ngay trong thư mục dự án**. APK ký bằng `keystore/applemewmew.jks` có sẵn nên chữ ký khớp cơ chế chống crack (`CopyrightGuard`).

### C. Cách nâng cấp nhanh từ DLS25 sang DLS26 (Khi game đổi màu nút giao diện)
- **Không cần sửa code AI**:
  - Trên **APK điện thoại**: Mở Menu Nổi trong game DLS26 → bấm **`✂ CẮT NÚT MẪU`** → chọn tên ô (`home_career`, `lobby_play`, `pre_match_play`, `post_match_continue`...) → dùng ngón tay khoanh nút mới trên màn hình và bấm **Lưu** (có thể bấm *Sao chép gói mẫu* trong tab Cài đặt để xuất chuỗi cấu hình chia sẻ cho máy khác).
  - Trên **PC giả lập**: Chạy `1_Mo_Bang_Dieu_Khien_Cat_Mau.bat` mở Web Studio → chụp màn hình & kéo chuột cắt lại các nút đổi màu → lưu thành Preset `dls26-mac-dinh`.
