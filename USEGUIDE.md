# Hướng dẫn làm việc với repo

Tài liệu này dành cho người (và trợ lý AI) làm việc trên repo. Đọc hết trước khi sửa code. README.md là tài liệu cho người dùng; file này là quy tắc làm việc.

## Dự án

Điều khiển cửa cuốn 433MHz bằng điện thoại Android qua Bluetooth Low Energy.

- ESP32-S3 đặt gần cửa, phát lại mã cố định của remote RF gốc, giống như một remote thứ hai. Không đấu dây vào hộp điều khiển cửa.
- App Android kết nối BLE, mặc định hiện bốn nút như remote: Lên, Xuống, Khóa, Mở khóa. Remote không có nút Dừng: bấm Khóa khi cửa đang chạy thì cửa dừng, phải bấm Mở khóa rồi mới Lên hoặc Xuống được. Admin thêm, sửa, xóa được nút (tối đa 8), mỗi nút có tên, biểu tượng và mã RF riêng; danh sách nút lưu trên board nên máy nào cũng thấy giống nhau.
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
│       ├── remote_buttons.*                  Danh sách nút điều khiển (id, biểu tượng, tên) trong NVS
│       ├── rf.h, rf.cpp                      Lớp RF: phát bằng rc-switch, tự giải mã lúc học mã, lưu mã trong NVS
│       └── power.h, power.cpp                Đo pin LiPo qua GPIO4 cho INFO
├── tools/qr-viewer.html                      Đọc mã QR từ board qua Web Serial
└── docs/                                     Ghi chú kế hoạch ban đầu
```

## Phần cứng

| Linh kiện | Ghi chú |
|---|---|
| LilyGO T-Display-S3 | Board ESP32-S3, bỏ màn hình. Nút BOOT = GPIO0, nút KEY = GPIO14 |
| Bộ MX-433: FS1000A (phát) | Cấp 5V. Phát lại mã của remote. Anten dây 17,3cm |
| Bộ MX-433: XY-MK-5V / MX-05V (thu) | Cấp 5V. Chỉ dùng khi học mã từ remote. DATA ra mức 5V nên phải qua cầu phân áp 10k/20k trước khi vào GPIO |
| Adapter 5V USB-C | Nguồn chính |
| Pin LiPo 3,7V, cổng JST 1,25mm | Nguồn dự phòng: giữ board chạy khi mất điện. Đo qua GPIO4 (cầu phân áp có sẵn trên board) |
| Điện trở 10kΩ và 20kΩ | Cầu phân áp cho DATA mạch thu |

DATA phát nối GPIO13, DATA thu nối GPIO12 qua cầu phân áp (ghi trong `config.h` và README). Chân 5V của board chỉ có điện khi cắm USB, nên lúc chạy pin hai mạch RF tắt.

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
| 6 | RF thật và nguồn: học mã từ remote, phát bằng `rc-switch`, đo pin dự phòng | Đang làm |
| 7 | Nhiều điện thoại: giao diện admin thêm, đổi tên, thu hồi | Xong |
| 9 | Tầm xa BLE: phát +20 dBm, thêm quảng bá trên LE Coded PHY | Đang làm |

Bản phát hành: `v0.0.1` (giai đoạn 1–3, RF thật học từ app, nút tùy chỉnh). Giai đoạn 4 và 6 chưa xong.

Giai đoạn 3 gồm: giao thức và crypto ghép đôi (có unit test), lưu khóa trong Android Keystore, GATT client và tự kết nối lại, màn hình ghép đôi, máy quét QR offline, gate quyền Bluetooth, design tokens và components, icon app, màn hình điều khiển (trạng thái và bốn nút, không cuộn) và sheet Cài đặt (thông tin thiết bị, Thêm điện thoại cho admin, Quên thiết bị). Đã chạy thử trên máy thật với board: bốn lệnh tới board và trả `0x00`, Thêm điện thoại mở ghép đôi, ẩn app thì ngắt kết nối, mở lại thì tự kết nối.

Giai đoạn 4, số đo ban đầu (tablet Android, đo bằng logcat và serial log): từ lúc mở app tới lúc sẵn sàng mất 1,45 đến 2,2 giây. Trong đó khởi động app tới lúc gọi `connect()` khoảng 0,32 giây; kết nối BLE 0,15 đến 0,98 giây, tùy chu kỳ quảng bá của ESP; trao đổi MTU khoảng 0,65 giây, lần nào cũng vậy; discover, bật notify và đọc CHALLENGE khoảng 0,3 giây. Lệnh hằng ngày chỉ 18 byte, vừa MTU mặc định, nên có thể bỏ `requestMtu` khi kết nối hằng ngày; chỉ ghép đôi mới cần MTU lớn.

Giai đoạn 4, đã làm:

- Bỏ trao đổi MTU khi kết nối hằng ngày, xin chu kỳ kết nối ngắn, ghi log thời gian (tag `DoorTiming`, chỉ bản debug).
- Ô Quick Settings mở app.
- `INFO` báo nguồn: firmware notify khi giá trị đổi, giá trị giả đặt bằng lệnh serial `power mains|battery [0-100]`. App đọc `INFO` ngay sau khi sẵn sàng, nên không làm chậm lúc bấm được. Khi board chạy pin, app hiện "Mất điện" kèm phần trăm pin và làm mờ bốn nút. Đã chạy thử trên máy thật: đổi nguồn qua serial thì app đổi theo ngay, mở app lúc board đang chạy pin thì hiện đúng.
- App gọi `connect()` ngay khi đọc xong thiết bị đã lưu, không chờ màn điều khiển vẽ xong. `MainActivity` lấy cùng `ControlViewModel` với màn điều khiển (theo key) và gọi `start()` sớm; màn điều khiển vẫn tự gọi `start()` khi người dùng vừa cấp quyền hay bật Bluetooth trên màn hình.
- Đọc thiết bị đã lưu một lần ngay khi tạo `RootViewModel` trong `onCreate`, chặn main 40–80 ms (lúc splash của hệ thống còn che), nên `connect()` được gọi ngay trong `onStart`, trước lần vẽ đầu. Bỏ trạng thái Loading và điều kiện giữ splash. Đọc bất đồng bộ thì DataStore xong sau 30–55 ms nhưng kết quả phải chờ main vẽ xong khoảng 170 ms; Keystore chỉ mất khoảng 5 ms.
- Log `DoorTiming` ghi từng bước của kết nối và setup dạng `chờ+chạy tiếp`: thời gian chờ callback Bluetooth, rồi thời gian từ lúc callback về tới lúc coroutine chạy tiếp trên main.

Giai đoạn 4, số đo sau khi bỏ MTU (cùng tablet, force-stop rồi mở lại, 10 lần): từ `start()` tới sẵn sàng 1,06 đến 1,28 giây; kết nối 0,47 đến 0,59 giây; setup 0,51 đến 0,75 giây. Tính từ lúc chạm icon thì khoảng 1,6 đến 1,9 giây, vì bản release mất 0,58 đến 0,77 giây từ lúc tạo process tới lúc gọi `connect()` (bản debug chậm gấp khoảng 5 lần, không dùng để đo đoạn này). Setup lâu hơn trước vì Android discover lại toàn bộ dịch vụ mỗi lần (ESP32-S3 là Bluetooth 5.0, Android không dùng cache) và mấy bước discover đầu chạy ở chu kỳ kết nối chậm.

Giai đoạn 4, số đo sau khi gọi `connect()` sớm (cùng tablet, bản release, 8 lần mỗi bản): từ lúc tạo process tới `connect()` còn 605–646 ms, trước là 652–739 ms, tức nhanh hơn khoảng 70 ms. Bản release có bật log `DoorTiming` (bản tạm, không commit): kiểm tra thiết bị đã lưu lúc mở app mất 206–239 ms, `start()` chạy sau khi tạo process 503–547 ms, từ `start()` tới sẵn sàng 924–1199 ms, trong đó setup 684–792 ms. Log của `BluetoothGatt` cho thấy từ `connect()` tới lúc có kết nối dao động 133–578 ms, có kết nối rồi thì app nhận gần như ngay.

Giai đoạn 4, số đo sau khi đọc thiết bị đã lưu ngay trong `onCreate` (cùng cách đo, 8 lần mỗi bản): từ lúc tạo process tới `connect()` còn 403–484 ms, trước là 590–773 ms. Màn hình hiện ra muộn hơn khoảng 50 ms (902–987 ms, trước 870–919 ms). Bản có log: đọc 41–77 ms, `start()` ở +320–359 ms, sẵn sàng ở khoảng +1,30 đến +1,45 giây sau khi tạo process, trước là khoảng +1,45 đến +1,70 giây. Kết nối giờ chạy cùng lúc với lần vẽ đầu: 3 trong 8 lần, có kết nối rồi app còn chờ main thêm 256–305 ms.

Giai đoạn 4, số đo từng bước (bản release có log, 8 lần): connect chờ 140–626 ms, rồi chờ main thêm 18–305 ms (5 trong 8 lần trên 139 ms, vì main đang vẽ màn đầu). Discover chờ 359–624 ms, là bước lâu nhất. Bật notify CHALLENGE, bật notify STATUS và đọc CHALLENGE mỗi bước chờ 19–38 ms. Sau discover, phần chờ main chỉ 3–31 ms mỗi bước. Từ `start()` tới sẵn sàng 938–1453 ms.

Cách đo: app cài bằng `adb install` chạy chưa biên dịch (`run-from-apk`), chậm gấp khoảng 3 lần. Trước khi đo phải chạy `adb shell cmd package compile -m speed -f com.trananh.rollingdoor`, rồi bỏ lần mở đầu tiên. Bản release không có log `DoorTiming`, nên đo bằng log hệ thống: `Start proc` của `ActivityManager` và `connect()`, `onClientConnectionState()` của `BluetoothGatt`.

Giai đoạn 4, số đo sau khi chạy `DoorLink.open()` trên `Dispatchers.Default` (commit `004cbc8`, tablet SM-T225, bản debug, 8 lần): có kết nối rồi chỉ còn chờ main 2–3 ms, trước là 18–305 ms. Discover 623–681 ms, chậm hơn trước vì bảng GATT có thêm `BUTTONS`; kết nối 162–786 ms.

Vì sao discover lâu (log `bt_bta_gattc` của tablet và serial log của board): tablet mở kết nối ở chu kỳ 48,75 ms, khoảng 445 ms sau mới đổi sang 7,5 ms để discover, rồi 15 ms khi app xin ưu tiên cao. Trước khi đổi, mỗi lượt hỏi đáp ATT mất khoảng 96 ms; sau khi đổi khoảng 15 ms. Khoảng 445 ms là do Android chạy lần lượt các thủ tục LL (đọc tính năng, phiên bản, rồi cập nhật thông số chờ tới instant), board không rút ngắn được. Android cũng không dùng cache GATT cho board: log ghi `Device LMP version 0x09 < Bluetooth 5.1. Ignore database cache read`, tức cache theo Database Hash chỉ dùng cho thiết bị từ Bluetooth 5.1, mà ESP32-S3 là 5.0. Bật `CONFIG_BT_NIMBLE_GATT_CACHING` không giúp được.

Giai đoạn 4, board tự xin MTU 255 ngay trong `onConnect`: chạy song song với discover của Android chứ không chặn nó, và với MTU lớn thì mỗi lượt đọc được nhiều characteristic 128-bit hơn. Số đo (cùng cách, 8 lần): discover 562–604 ms, setup 653–744 ms, trước là 699–798 ms, tức nhanh hơn khoảng 60 ms. Ghép đôi và sửa nút (lệnh `0C`, cần MTU lớn) vẫn chạy. Bản debug cho tổng thời gian tới sẵn sàng tăng, vì discover xong sớm hơn nên trùng lúc main đang vẽ màn đầu (bản debug vẽ chậm khoảng 5 lần); muốn đo tổng phải dùng bản release.

Việc còn lại, làm cuối giai đoạn 4:

- Discover vẫn khoảng 0,58 giây, phần lớn là chờ Android đổi chu kỳ. Muốn bỏ hẳn bước này thì phải bond, vì Android chỉ cache GATT cho thiết bị đã bond. Để sau. Thiết kế dự kiến:
  - Bond Just Works chỉ để Android cache GATT; bảo mật vẫn là HMAC, không characteristic nào đòi mã hóa. Board chỉ giữ bond của máy đã gửi lệnh HMAC hợp lệ trên kết nối đó (bond lạ thì xóa và ngắt), tối đa 8 bond (`CONFIG_BT_NIMBLE_MAX_BONDS`), lưu địa chỉ của máy theo ô khóa để thu hồi (`07`) và giữ KEY 10 giây xóa luôn bond. App bond sau Ping xác nhận lúc ghép đôi; máy cũ bond qua một dòng trong Cài đặt; thiếu characteristic sau discover thì `refresh()` rồi discover lại.
  - Rủi ro: Android có thể tự mã hóa link mỗi lần kết nối lại (thêm khoảng 150–200 ms lúc chu kỳ còn 48,75 ms); bond lệch (board mất bond, máy còn) làm kết nối rớt liên tục, mà app không tự xóa bond được từ Android 13, người dùng phải bỏ ghép đôi trong Cài đặt Bluetooth; hộp thoại ghép đôi của hệ thống hiện địa chỉ MAC vì board không quảng bá tên.
  - Bước đầu, chưa commit: bật bond trên board, app debug gọi `createBond()` một lần, đo 8 lần (discover, có mã hóa không), thử xóa bond trên board xem máy có kết nối lại được không. Chỉ làm tiếp nếu setup giảm từ 300 ms trở lên và bond lệch phục hồi được.
- Kết nối dao động 0,13–0,79 giây không phải do chu kỳ quảng bá: board đã quảng bá 20–30 ms (nhánh `esp32`, commit `a82a387`) mà số đo không đổi. Cần xem thông số kết nối phía Android (HCI snoop log).

Giai đoạn 6, đã làm (nhánh `esp32`): thay lớp RF giả bằng `rc-switch`, phát lặp 10 lần mỗi lệnh. Học mã qua lệnh serial `rf learn <nút>`: mạch thu chỉ bật lúc học, giải mã được cùng một mã hai lần liên tiếp mới lưu vào NVS (namespace `rf`), log không in giá trị mã. Nút chưa có mã thì lệnh trả `03`. `rf verify` phát từng mã đã học rồi cho mạch thu của board tự giải lại, so với mã đã lưu, để chắc mạch phát phát đúng mã của nút đã bấm trên remote (cũng cảnh báo nếu hai nút trùng mã). Lệnh kiểm tra phần cứng: `rf selftest` (như trên nhưng với mã giả) và `rf scan` (đếm xung trên chân thu).

Bài học khi thử với remote thật: remote là mã cố định, giao thức 1 của rc-switch, 24 bit, T khoảng 300 µs; đã học đủ bốn nút. Phần thu của rc-switch không dùng được: `send()` tắt bộ thu của chính đối tượng đó, và mạch thu rẻ thỉnh thoảng làm mất một xung ngắn khiến rc-switch bỏ cả khung. Vì vậy firmware tự giải mã giao thức 1 và ghép lại xung bị mất; rc-switch chỉ còn dùng để phát.

Giai đoạn 6, học mã từ app admin (firmware ở nhánh `esp32`, app ở nhánh `android`): lệnh `09` với `[01–04]` bật mạch thu cho nút đó, `[00]` hủy. Board trả `09 00` khi bắt đầu nghe, rồi notify `STATUS` `81 00` khi lưu được mã hoặc `81 03` khi hết 15 giây. Máy admin ngắt kết nối thì board tự hủy. Lệnh `0A` `[01–04]` xóa mã một nút (trả `03` nếu đang học). `INFO` thêm byte thứ 3 là bitmask nút đã học, notify lại khi học hay xóa mã (cả qua serial). Trong app: Cài đặt, Học lệnh remote, danh sách bốn nút, chạm một dòng để học (hoặc học lại); nút chưa học có nút Học lệnh ở cuối dòng, nút đã học ghi Đã học kèm thùng rác để xóa mã; đóng sheet giữa chừng thì gửi `09 00`. Lệnh serial `rf ...` giữ nguyên. Đã chạy thử trên tablet với board và remote thật: học được, hết 15 giây, hủy giữa chừng, không bấm remote thì sau 15 giây báo không bắt được mã và giữ nguyên trạng thái, xóa rồi bấm nút đó thì app báo "Nút này chưa học lệnh" (app tự phân biệt lỗi `03` nhờ byte nút đã học trong `INFO`: nút đã học mà bị `03` thì là board đang bận), học lại thì `rf verify` OK cả bốn nút.

Giai đoạn 6, nút tùy chỉnh (firmware ở nhánh `esp32`, app ở nhánh `android`): board giữ tối đa 8 nút trong NVS (namespace `buttons`), mỗi nút có id 1–8 (cũng là ô mã RF, key `c1`–`c8` trong namespace `rf`; mã học trước đó dưới key `UP`, `DOWN`, `LOCK`, `UNLOCK` tự chuyển sang nút 1–4 lần khởi động đầu), biểu tượng (10 loại, màu theo biểu tượng) và tên UTF-8 tối đa 32 byte. Board mới có sẵn bốn nút mặc định, tên trống, app hiện tên của biểu tượng theo ngôn ngữ máy. Lệnh mới: `0B` bấm nút `[id]` (`01`–`04` vẫn bấm nút 1–4), `0C` thêm hoặc sửa nút `[id][biểu tượng][tên]`, `0D` xóa nút cùng mã của nó; `09`, `0A` nhận id 1–8. Args tăng lên 34 byte, nên app xin MTU lớn trước khi gửi `0C` (chỉ lệnh này vượt MTU mặc định). Characteristic mới `BUTTONS` (`…0007`, chỉ đọc) trả về danh sách; `INFO` thêm byte thứ 4 là số phiên bản danh sách, board notify `INFO` trước `STATUS` của lệnh. App lưu danh sách trong DataStore và chỉ đọc lại khi số phiên bản đổi. Trong app: Cài đặt, Nút điều khiển, danh sách nút và Thêm nút; trang của từng nút gồm tên, biểu tượng, Lưu, Học lệnh (xóa lệnh ở cuối dòng) và Xóa nút (có bước xác nhận). Màn điều khiển xếp 2 nút một hàng, các hàng chia đều chiều cao, nút lẻ cuối chiếm cả hàng. Lệnh serial: `buttons`, `rf learn <1-8>`. Đã build firmware và app, unit test qua, chạy thử trên board được.

Giai đoạn 6, pin: bỏ phát hiện điện lưới (không nối thêm dây), nên app bỏ luôn báo "Mất điện" và làm mờ nút. Board đo pin qua GPIO4 của T-Display-S3 (cầu phân áp 1/2 có sẵn), `analogReadMilliVolts` nhân 2, trung bình 16 mẫu, 5 giây một lần; đổi ra phần trăm theo bảng điện áp LiPo (4,2V = 100%, 3,3V = 0%), chỉ notify `INFO` khi lệch từ 2%. Khi cắm USB, GPIO4 đo phía mạch sạc (đo được khoảng 4,6V) chứ không phải pin, nên trên 4,4V board báo `FE` (đang sạc), dưới 2,5V báo `FF` (không có pin). Byte đầu của `INFO` luôn là `00`. App hiện dòng Pin trong Cài đặt: "Đang sạc" hoặc phần trăm. Lệnh serial `power` (giá trị giả) đổi thành `battery` (in điện áp và phần trăm). Đã thử trên board: cắm USB đọc 4610 mV, báo đang sạc. Rút USB thì app nhận `INFO` `00 ff …`: GPIO4 đọc gần 0V khi chạy pin. Theo ví dụ của LilyGO, chạy pin thì phải bật GPIO15 (`LCD_POWER_ON`) lên mức cao; firmware bật nó chỉ trong lúc đo (khoảng 10 ms) rồi tắt, vì chân này cũng cấp nguồn cho màn hình. Chưa thử lại bản này lúc chạy pin.

Giai đoạn 6, nhắc học lệnh: board mới chưa có mã nào nên các nút mờ mà không nói lý do. Màn điều khiển giờ ghi dưới dòng trạng thái: chưa học nút nào thì "Chưa học lệnh từ remote nên các nút chưa dùng được." (máy thường thêm "Nhờ quản trị viên học lệnh."), học một phần thì "Nút mờ là nút chưa học lệnh."; máy quản trị viên có link Học lệnh mở thẳng trang Nút điều khiển. Đã thử trên tablet.

Giai đoạn 6, còn lại: thử phát ở cửa thật (đã phát được, chưa kiểm tra cửa có nhận không), kiểm tra phần trăm pin lúc chạy pin.

Giai đoạn 7, xong (firmware ở nhánh `esp32`, app ở nhánh `android`):

- Lệnh mới: `08` Rời board (máy nào cũng gửi được, board xóa ô của chính máy gửi, không nhận key id), `0E` Xem danh sách máy (máy nào cũng gửi được), `0F` Đổi tên máy `[key id][tên 0–32 byte]` (admin đổi mọi máy, máy thường chỉ đổi tên chính nó), `10` Chuyển quyền admin `[key id]` (admin cũ thành máy thường). Characteristic mới `PHONES` (`…0008`, chỉ đọc): rỗng cho tới khi kết nối đó gửi `0E`; kết nối khác đọc ra rỗng.
- Tên máy lưu trong NVS cùng namespace `keys`, key `n0`–`n7`, không đổi blob `Slot` nên các máy đã ghép đôi giữ nguyên khóa. Ghép đôi xong, app gửi `0F` đặt tên theo tên thiết bị trong Cài đặt Android. Máy chưa có tên thì app hiện "Điện thoại <số ô + 1>".
- Admin rời đi khi còn máy khác thì board trả `04`, app báo phải đặt máy khác làm quản trị viên trước. Admin là máy cuối thì rời được, board mở lại ghép đôi. Không kết nối được board lúc quên: máy nào cũng được "Vẫn quên" (ô còn trên board cho tới khi admin thu hồi). Admin thì có cảnh báo: board sẽ không còn quản trị viên, muốn có lại phải giữ KEY 10 giây. Lúc đầu admin bị chặn hẳn, nhưng board hỏng thì admin kẹt mãi, không quên được để ghép đôi board mới.
- `0E` cho mọi máy chứ không chỉ admin, vì máy được chuyển quyền cần biết vai trò mới: app đọc danh sách mỗi lần mở Cài đặt và cập nhật vai trò lưu trong DataStore.
- Nhiều kết nối cùng lúc (tối đa 3, `config::kMaxConnections`): trước đây board chỉ nhận một kết nối và ngừng quảng bá, nên một người mở app thì người khác thấy "Ngoài vùng". Giờ board quảng bá lại trong `onConnect` khi còn chỗ. Mỗi kết nối có nonce CHALLENGE riêng (đọc qua `onRead`, notify riêng cho kết nối đó), nhận `STATUS` của lệnh do chính nó gửi, và đọc `PAIRING`, `PHONES` của riêng nó; `INFO` notify cho tất cả. Đọc dài (`PAIRING`, `PHONES`) chỉ gọi `onRead` ở lượt đầu, nên hai máy đọc cùng lúc có thể lẫn giá trị; hiếm, và app kiểm tra rồi đọc lại. Chỉ giữ một ô chờ xác nhận lúc ghép đôi: hai máy ghép đôi cùng lúc thì máy trước bị bỏ.
- Mã mời thay cho việc lấy mã QR qua máy tính: lệnh `11` (admin), board tạo 8 số ngẫu nhiên dùng một lần trong 5 phút (mã mới thay mã cũ, dùng xong hay ghép đôi xong thì hủy). Khóa ghép đôi là `HMAC-SHA256(key = 8 số dạng ASCII, "RDINVITE")` lấy 16 byte đầu, thay cho setup secret, phần còn lại của ghép đôi giữ nguyên. Admin đọc `PAIRING` ngay sau lệnh: `[02][salt 16][8 số XOR HMAC(khóa admin, "RDINVITE" ‖ salt)]`, nên 8 số không đi qua sóng ở dạng rõ. Máy admin hiện mã QR (dạng `RDOOR1:<MAC>:<khóa>`, vẽ bằng ZXing core) và 8 số; máy mới quét hoặc chọn Nhập mã rồi gõ 8 số. Gõ số thì app không có MAC, nên tìm board đang quảng bá cờ ghép đôi. Đóng trang mã thì app đọc lại danh sách, nên thấy ngay máy vừa ghép đôi. Mã QR gốc của board vẫn dùng được (máy đầu tiên, giữ BOOT 3 giây).
- Trong app: Cài đặt, Điện thoại (thay dòng Thêm điện thoại cũ): danh sách máy (nhãn Quản trị viên, Máy này) và Thêm điện thoại (hiện mã mời); trang của từng máy có tên và Lưu, với máy khác thì thêm Đặt làm quản trị viên và Thu hồi (đều có bước xác nhận). Quên thiết bị gửi `08` trước rồi mới xóa khóa trên máy.
- Đã chạy thử trên tablet với board và một điện thoại thứ hai: danh sách máy, đổi tên, mã mời (máy thứ hai ghép đôi được và tự đặt tên theo máy), hai máy cùng kết nối, chuyển quyền, thu hồi, quên thiết bị.

Giai đoạn 9, đang làm (firmware ở nhánh `esp32`, app ở nhánh `android`):

- Board phát +20 dBm (`config::kBleTxPowerDbm`, mặc định của ESP32-S3 là +9 dBm), áp cho quảng bá và kết nối. Board cắm điện, có pin 3700 mAh dự phòng, nên ưu tiên tầm xa hơn dòng tiêu thụ. Lưu ý: BLE là hai chiều, chiều điện thoại gửi tới board vẫn theo công suất của điện thoại, nên tầm xa tăng ít hơn mức 11 dB.
- Quảng bá mở rộng của NimBLE (`CONFIG_BT_NIMBLE_EXT_ADV`, 2 bộ): bộ 0 là quảng bá cũ trên 1M PHY (service UUID, cờ ghép đôi trong scan response, máy nào cũng thấy, app quét bộ này lúc ghép đôi); bộ 1 trên LE Coded PHY S8, connectable, không scannable nên cờ ghép đôi nằm ngay trong gói quảng bá, chu kỳ 50–100 ms. Cả hai cùng địa chỉ, nên app vẫn kết nối theo MAC đã lưu. Chế độ mở rộng không có `advertiseOnDisconnect`, nên `startAdvertising()` được gọi trong `onConnect` và `onDisconnect`: còn chỗ thì bật cả hai, đủ 3 máy thì tắt cả hai. Log ghi kết nối đang ở PHY nào.
- App: máy có Coded PHY (`isLeCodedPhySupported`) thì `connectGatt` với mặt nạ 1M + Coded, Android nghe bộ nào trước thì vào bộ đó. Lúc ngoài vùng, autoConnect chỉ chờ trên 1M, nên máy có Coded PHY thử kết nối trực tiếp lặp lại, mỗi lần 30 giây. Máy không có Coded PHY chạy như cũ.
- Lệnh serial mới `keys admin <ô>` và `keys revoke <ô>` (cần cáp USB nên an toàn như `wipe`): gỡ app trên máy quản trị mà chưa bấm Quên thiết bị thì board vẫn giữ ô quản trị cũ, ghép đôi lại chỉ được máy thường. Lúc chuyển vivo sang bản release đã gặp đúng chuyện này và sửa bằng `keys admin 2` rồi `keys revoke 0`. `revoke` từ chối ô quản trị, để board không bao giờ hết quản trị viên.
- Đã nạp firmware: log khởi động báo quảng bá trên 1M và Coded PHY ở 20 dBm. Vivo (V2324HA, Android 16) có Coded PHY. Chưa đo tầm xa.
