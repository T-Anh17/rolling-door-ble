# Hướng dẫn làm việc với repo

Tài liệu này dành cho người (và trợ lý AI) làm việc trên repo. Đọc hết trước khi sửa code. README.md là tài liệu cho người dùng; file này là quy tắc làm việc.

## Dự án

Điều khiển cửa cuốn 433MHz bằng điện thoại Android qua Bluetooth Low Energy.

- ESP32-S3 đặt gần cửa, phát lại mã cố định của remote RF gốc, giống như một remote thứ hai. Không đấu dây vào hộp điều khiển cửa.
- App Android kết nối BLE, hiện bốn nút như remote: Lên, Xuống, Khóa, Mở khóa. Remote không có nút Dừng: bấm Khóa khi cửa đang chạy thì cửa dừng, phải bấm Mở khóa rồi mới Lên hoặc Xuống được.
- BLE là kênh điều khiển duy nhất. WiFi mặc định tắt, chỉ bật khi cập nhật firmware (OTA).
- Mọi lệnh được xác thực bằng khóa riêng của từng điện thoại (HMAC-SHA256) và nonce dùng một lần. Ghép đôi bằng mã QR do từng board tự sinh.
- Giao thức BLE và cách ghép đôi mô tả đầy đủ trong README.md. Sửa giao thức thì sửa cả firmware, app và README trong cùng một giai đoạn.

## Cấu trúc thư mục

```
rolling-door-ble/
├── README.md                                 Tài liệu cho người dùng (tiếng Anh)
├── USEGUIDE.md                               File này
├── LICENSE                                   MIT, chỉ áp dụng cho mã nguồn
├── android/                                  App Android (Kotlin, Jetpack Compose)
│   └── app/src/main/java/com/trananh/rollingdoor/
│       ├── ble/                              GATT client, kết nối cửa, quét, phiên ghép đôi
│       ├── crypto/                           HKDF, ghép đôi ECDH, ký lệnh, Android Keystore
│       ├── data/                             DeviceRepository (DataStore + PhoneKeyStore)
│       ├── protocol/                         UUID, khung lệnh, phân tích mã QR
│       ├── tile/                             Ô Quick Settings: chạm vào là mở app
│       └── ui/                               Theme, components, gate quyền Bluetooth, ghép đôi, điều khiển
│   └── app/src/brand/                        Icon và logo splash riêng (không có trong repo)
├── firmware_esp/rolling-door-firmware-esp/   Firmware ESP32-S3 (PlatformIO, Arduino)
│   ├── platformio.ini
│   └── src/
│       ├── config.h                          UUID, độ dài khung, chân nút, thời gian
│       ├── ble_server.*                      GATT server (NimBLE)
│       ├── auth.*, key_store.*               Kiểm tra HMAC, bảng 8 khóa trong NVS
│       ├── pairing.*, device_secret.*        Ghép đôi, setup secret và chuỗi QR
│       ├── protocol.*, buttons.*, console.*  Mã lệnh, nút BOOT/KEY, lệnh serial
│       ├── rf.h, rf_fake.cpp                 Lớp RF (hiện là bản giả, chỉ in log)
│       └── power.h, power_fake.cpp           Trạng thái nguồn cho INFO (hiện là bản giả, đặt bằng lệnh serial)
├── tools/qr-viewer.html                      Đọc mã QR từ board qua Web Serial
└── docs/                                     Ghi chú kế hoạch ban đầu
```

## Phần cứng

| Linh kiện | Ghi chú |
|---|---|
| LilyGO T-Display-S3 | Board ESP32-S3, bỏ màn hình. Nút BOOT = GPIO0, nút KEY = GPIO14 |
| Bộ MX-433: FS1000A (phát) | Cấp 5V. Phát lại mã của remote. Anten dây 17,3cm |
| Bộ MX-433: XY-MK-5V (thu) | Cấp 5V. Chỉ dùng khi học mã từ remote. DATA ra mức 5V nên phải qua cầu phân áp 10k/20k trước khi vào GPIO |
| Adapter 5V USB-C | Nguồn chính |
| Pin LiPo 3,7V, cổng JST 1,25mm | Nguồn dự phòng, chỉ để báo mất điện |
| Điện trở 10kΩ và 20kΩ | Cầu phân áp cho DATA mạch thu và cho mạch phát hiện điện lưới |

Chân GPIO cho DATA phát, DATA thu và phát hiện điện lưới chưa chốt; sẽ chọn ở giai đoạn 6 và ghi vào `config.h` cùng README.

## Build trên Windows

Lệnh dưới đây chạy trong PowerShell.

### Firmware

Cần PlatformIO. Nếu cài qua extension VS Code mà `pio` chưa có trong PATH, dùng `$env:USERPROFILE\.platformio\penv\Scripts\pio.exe`.

```powershell
cd firmware_esp\rolling-door-firmware-esp
pio run                      # chỉ biên dịch
pio run -t upload            # biên dịch và nạp
pio device monitor           # xem log serial, 115200 baud
pio run -t erase             # xóa toàn bộ flash: sinh setup secret và mã QR mới
```

### App Android

Cần Android Studio (SDK và JDK). minSdk 26, targetSdk 36. BLE không chạy trên emulator, phải dùng máy thật bật USB debugging.

```powershell
cd android
.\gradlew.bat assembleDebug        # build APK debug
.\gradlew.bat installDebug         # build và cài lên điện thoại đang cắm
.\gradlew.bat testDebugUnitTest    # chạy unit test
```

## Cách làm việc

- **Trả lời bằng tiếng Việt.** Code, tên biến, comment và commit message viết bằng tiếng Anh. README.md viết bằng tiếng Anh.
- **Làm từng giai đoạn.** Chỉ làm giai đoạn đang làm, không làm trước việc của giai đoạn sau. Xong mỗi giai đoạn thì cập nhật mục "Trạng thái" bên dưới và Roadmap trong README.md.
- **Nêu trước, làm sau.** Trước khi sửa, liệt kê các file sẽ tạo, sửa hoặc xóa kèm lý do ngắn, rồi chờ đồng ý.
- **Nhánh:** `main` ổn định, `dev` phát triển hằng ngày, `android` cho app, `esp32` cho firmware. Gộp `android`/`esp32` vào `dev`; gộp `dev` vào `main` khi một giai đoạn chạy được, rồi gắn tag.
- **Ngôn ngữ app:** theo ngôn ngữ điện thoại; tiếng Việt thì dùng `values-vi`, còn lại tiếng Anh.

## Mã nguồn mở

Repo này để Public. Các quy tắc sau bắt buộc cho mọi thay đổi:

- **Không bao giờ commit** mật khẩu WiFi, mật khẩu OTA, khóa bí mật (setup secret, khóa điện thoại) hay mã RF đã học. Các giá trị này chỉ nhập lúc chạy và lưu trong NVS của ESP hoặc Android Keystore.
- **Giá trị cần lúc biên dịch** thì đặt trong `secrets.h`. File này nằm trong `.gitignore`; commit kèm một bản mẫu `secrets.example.h` chỉ chứa giá trị giả.
- **Không đưa vào repo** ảnh chụp bên trong remote hay vị trí DIP switch, vì đó chính là mã của cửa. Không đưa địa chỉ nhà hay ảnh nhận ra được ngôi nhà. Không đưa mã QR thật, ảnh chụp mã QR hay log serial có in mã QR.
- **Icon app là logo riêng, không nằm trong license.** Logo đặt trong `android/app/src/brand/` (đã ignore), Gradle dùng nó ghi đè icon mặc định trong `src/main`. Không chép logo vào `src/main` hay bất cứ chỗ nào được commit.
- **Dữ liệu mẫu** trong test và preview phải rõ là giả (ví dụ secret `00 01 02 … 0F`), không lấy từ board thật.
- **Trước mỗi lần commit**, rà lại toàn bộ phần thay đổi (`git diff --staged`, cả file mới chưa theo dõi) để chắc không có thông tin nhạy cảm, và nói rõ là đã rà.

## Trạng thái các giai đoạn

| Giai đoạn | Nội dung | Trạng thái |
|---|---|---|
| 1 | Khung BLE: GATT server trên ESP32, lớp RF giả | Xong |
| 2 | Ghép đôi bằng QR, bảng khóa, kiểm tra HMAC, quyền admin | Xong |
| 3 | App Android: ghép đôi, màn hình bốn nút, tự kết nối | Xong |
| 4 | Hoàn thiện: dưới 1 giây từ lúc mở đến sẵn sàng, xử lý mất kết nối, trạng thái nguồn | Chưa |
| 5 | OTA qua WiFi do admin bật, từ chối khi chạy pin | Chưa |
| 6 | RF thật và nguồn: học mã từ remote, phát bằng `rc-switch`, lắp pin và mạch phát hiện điện lưới | Chưa |
| 7 | Nhiều điện thoại: giao diện admin thêm, đổi tên, thu hồi | Chưa |

Giai đoạn 3 gồm: giao thức và crypto ghép đôi (có unit test), lưu khóa trong Android Keystore, GATT client và tự kết nối lại, màn hình ghép đôi, máy quét QR offline, gate quyền Bluetooth, design tokens và components, icon app, màn hình điều khiển (trạng thái và bốn nút, không cuộn) và sheet Cài đặt (thông tin thiết bị, Thêm điện thoại cho admin, Quên thiết bị). Đã chạy thử trên máy thật với board: bốn lệnh tới board và trả `0x00`, Thêm điện thoại mở ghép đôi, ẩn app thì ngắt kết nối, mở lại thì tự kết nối.

Giai đoạn 4, số đo ban đầu (tablet Android, đo bằng logcat và serial log): từ lúc mở app tới lúc sẵn sàng mất 1,45 đến 2,2 giây. Trong đó khởi động app tới lúc gọi `connect()` khoảng 0,32 giây; kết nối BLE 0,15 đến 0,98 giây, tùy chu kỳ quảng bá của ESP; trao đổi MTU khoảng 0,65 giây, lần nào cũng vậy; discover, bật notify và đọc CHALLENGE khoảng 0,3 giây. Lệnh hằng ngày chỉ 18 byte, vừa MTU mặc định, nên có thể bỏ `requestMtu` khi kết nối hằng ngày; chỉ ghép đôi mới cần MTU lớn.

Giai đoạn 4, đã làm:

- Bỏ trao đổi MTU khi kết nối hằng ngày, xin chu kỳ kết nối ngắn, ghi log thời gian (tag `DoorTiming`, chỉ bản debug).
- Ô Quick Settings mở app.
- `INFO` báo nguồn: firmware notify khi giá trị đổi, giá trị giả đặt bằng lệnh serial `power mains|battery [0-100]`. App đọc `INFO` ngay sau khi sẵn sàng, nên không làm chậm lúc bấm được. Khi board chạy pin, app hiện "Mất điện" kèm phần trăm pin và làm mờ bốn nút. Đã chạy thử trên máy thật: đổi nguồn qua serial thì app đổi theo ngay, mở app lúc board đang chạy pin thì hiện đúng.
- App gọi `connect()` ngay khi đọc xong thiết bị đã lưu, không chờ màn điều khiển vẽ xong. `MainActivity` lấy cùng `ControlViewModel` với màn điều khiển (theo key) và gọi `start()` sớm; màn điều khiển vẫn tự gọi `start()` khi người dùng vừa cấp quyền hay bật Bluetooth trên màn hình.

Giai đoạn 4, số đo sau khi bỏ MTU (cùng tablet, force-stop rồi mở lại, 10 lần): từ `start()` tới sẵn sàng 1,06 đến 1,28 giây; kết nối 0,47 đến 0,59 giây; setup 0,51 đến 0,75 giây. Tính từ lúc chạm icon thì khoảng 1,6 đến 1,9 giây, vì bản release mất 0,58 đến 0,77 giây từ lúc tạo process tới lúc gọi `connect()` (bản debug chậm gấp khoảng 5 lần, không dùng để đo đoạn này). Setup lâu hơn trước vì Android discover lại toàn bộ dịch vụ mỗi lần (ESP32-S3 là Bluetooth 5.0, Android không dùng cache) và mấy bước discover đầu chạy ở chu kỳ kết nối chậm.

Giai đoạn 4, số đo sau khi gọi `connect()` sớm (cùng tablet, bản release, 8 lần mỗi bản): từ lúc tạo process tới `connect()` còn 605–646 ms, trước là 652–739 ms, tức nhanh hơn khoảng 70 ms. Bản release có bật log `DoorTiming` (bản tạm, không commit): kiểm tra thiết bị đã lưu lúc mở app mất 206–239 ms, `start()` chạy sau khi tạo process 503–547 ms, từ `start()` tới sẵn sàng 924–1199 ms, trong đó setup 684–792 ms. Log của `BluetoothGatt` cho thấy từ `connect()` tới lúc có kết nối dao động 133–578 ms, có kết nối rồi thì app nhận gần như ngay.

Cách đo: app cài bằng `adb install` chạy chưa biên dịch (`run-from-apk`), chậm gấp khoảng 3 lần. Trước khi đo phải chạy `adb shell cmd package compile -m speed -f com.trananh.rollingdoor`, rồi bỏ lần mở đầu tiên. Bản release không có log `DoorTiming`, nên đo bằng log hệ thống: `Start proc` của `ActivityManager` và `connect()`, `onClientConnectionState()` của `BluetoothGatt`.

Việc còn lại, làm cuối giai đoạn 4:

- Kiểm tra Keystore lúc mở app (`discardIncomplete`) chạy trước khi biết thiết bị đã lưu, nên `connect()` phải chờ. Cách sửa: báo thiết bị đã lưu ngay khi đọc xong DataStore, kiểm tra Keystore song song. Khóa mất thì lúc ký lệnh vẫn báo lỗi khóa.
- ESP xin chu kỳ kết nối ngắn ngay khi vừa kết nối, để discover chạy nhanh từ bước đầu. Setup giờ là đoạn lâu nhất.
- Kết nối dao động 0,13–0,58 giây không phải do chu kỳ quảng bá: board đã quảng bá 20–30 ms (nhánh `esp32`, commit `a82a387`) mà số đo không đổi. Cần xem thông số kết nối phía Android (HCI snoop log).

Giai đoạn 7, lỗ hổng cần sửa: "Quên thiết bị" hiện chỉ xóa khóa trên điện thoại, ô khóa vẫn nằm trong bảng của board. Lệnh `07` không cho admin tự thu hồi mình, nên admin quên thiết bị thì board không còn ai có quyền admin, và muốn lấy lại phải giữ KEY 10 giây, xóa sạch khóa của mọi máy. Cách sửa: khi quên thiết bị, app gửi lệnh mới nhờ board xóa ô của chính nó. Những điểm cần quyết khi thiết kế:

- Lệnh mới (dự kiến `08`, Rời board): máy nào cũng gửi được, ký bằng khóa của chính nó. Board xóa ô của máy gửi, không nhận key id, nên máy thường không xóa được ô của máy khác.
- Admin rời đi: nếu còn máy khác thì hoặc bắt admin chuyển quyền trước, hoặc board tự nâng máy cũ nhất lên admin. Nếu admin là máy cuối cùng thì board mở lại ghép đôi.
- Không kết nối được board lúc quên: app báo ô khóa sẽ còn trên board rồi vẫn cho quên trên máy. Với admin thì chặn hoặc cảnh báo mạnh hơn.
