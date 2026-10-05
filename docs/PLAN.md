# Kế hoạch điều khiển cửa cuốn bằng ESP32-S3

App Android kết nối BLE, ESP32-S3 phát lại mã RF 433MHz của remote. Cập nhật 05/10/2026.

## 1. Các điểm đã chốt

- **Lệnh điều khiển:** bốn nút Lên, Dừng, Xuống, Khóa, đúng với remote hiện tại.
- **Kết nối:** BLE là kênh điều khiển duy nhất. Không làm điều khiển từ xa qua internet.
- **WiFi:** chỉ dùng để cập nhật firmware (OTA).
- **Người dùng:** hiện tại một điện thoại, là admin. Thiết kế sẵn để thêm điện thoại sau này.
- **Board:** LilyGO T-Display-S3 đã tháo màn hình.
- **Nguồn:** adapter 5V là nguồn chính, pin 3,7V dự phòng chỉ để báo trạng thái. Cửa không có bình lưu điện nên khi mất điện cửa không chạy.
- **Phần RF:** làm sau khi mua linh kiện. Trước đó dùng lớp RF giả (ghi log Serial) để phát triển firmware và app.

## 2. Công nghệ

- **Firmware:** PlatformIO, Arduino, NimBLE-Arduino; thêm rc-switch ở giai đoạn RF.
- **App:** Android native, Kotlin và Jetpack Compose, tối thiểu Android 8 (API 26), thử nghiệm trên máy Android 11.
- **Quyền Bluetooth:** Android 11 trở xuống cần quyền vị trí và bật định vị khi quét thiết bị lúc ghép đôi; Android 12 trở lên dùng quyền Bluetooth riêng. App khai báo cả hai bộ quyền.
- **Module RF:** bộ thu phát MX-433MHz (FS1000A phát, XY-MK-5V thu để học mã). Cần xác nhận tần số trên linh kiện T1 của remote. Remote dùng mã cố định, có DIP switch 8 nấc 3 trạng thái.

## 3. WiFi cho OTA

- **Mặc định WiFi tắt.** ESP chỉ chạy BLE.
- **Bật khi cần:** admin gửi lệnh "Chế độ cập nhật" qua BLE, có xác thực. ESP bật WiFi, mở OTA trong 5 phút rồi tự tắt.
- **Nạp firmware:** từ máy tính trong cùng mạng LAN bằng PlatformIO (ArduinoOTA, có mật khẩu).
- **Cấu hình WiFi:** nhập SSID và mật khẩu từ app qua BLE, lưu vào NVS.
- Từ chối bật chế độ cập nhật khi đang chạy pin.

## 4. Nguồn và pin

Pin không giúp mở cửa khi mất điện. Pin giữ cho ESP tiếp tục chạy để app biết là đang mất điện.

- **Có điện:** adapter 5V qua USB-C cấp nguồn và sạc pin bằng mạch sạc có sẵn trên board.
- **Mất điện:** board tự chuyển sang pin, ESP không khởi động lại.
- **Pin:** một cell LiPo hoặc Li-ion 3,7V có mạch bảo vệ, cổng JST 1,25mm.
- **Nhận biết có điện:** chân 5V qua cầu phân áp vào một GPIO.
- **Đo pin:** GPIO4, chỉ đọc đúng khi không cắm USB, nên phần trăm pin chỉ hiện khi đang chạy pin.
- **Trên app:** có điện hiện "Nguồn điện". Mất điện hiện "Mất điện, cửa không hoạt động" kèm phần trăm pin, và làm mờ bốn nút.

## 5. Điện thoại và quyền admin

Firmware dùng bảng khóa nhiều ô ngay từ đầu để sau này thêm điện thoại không phải đổi giao thức.

- **Mỗi điện thoại một khóa riêng:** mỗi ô trong NVS gồm mã số, tên, vai trò (admin hoặc thường) và khóa 32 byte.
- **Điện thoại đầu tiên là admin:** ghép đôi bằng cách giữ nút BOOT (GPIO0) 3 giây, cửa sổ ghép đôi mở 60 giây.
- **Quyền admin:** bật chế độ cập nhật, cấu hình WiFi, học mã RF, thêm và thu hồi điện thoại.
- **Điện thoại thường:** chỉ gửi được bốn lệnh điều khiển cửa.
- **Thêm điện thoại (tương lai):** admin bấm "Thêm điện thoại" trong app, ESP mở ghép đôi 60 giây, máy mới nhận khóa riêng.
- **Mất điện thoại admin:** giữ nút thứ hai (GPIO14) 10 giây để xóa toàn bộ khóa rồi ghép đôi lại.

## 6. Giao thức BLE

| Characteristic | Kiểu | Nội dung |
|---|---|---|
| CHALLENGE | read, notify | Nonce ngẫu nhiên 16 byte, đổi sau mỗi lệnh |
| COMMAND | write | `[mã khóa 1 byte][lệnh 1 byte][HMAC-SHA256(khóa, nonce + lệnh) cắt 16 byte]` |
| STATUS | notify | `[lệnh][kết quả]` |
| INFO | read, notify | `[nguồn: điện hoặc pin][phần trăm pin]` |

| Mã lệnh | Chức năng | Ai được dùng |
|---|---|---|
| 01 | Lên | Mọi điện thoại đã ghép đôi |
| 02 | Dừng | Mọi điện thoại đã ghép đôi |
| 03 | Xuống | Mọi điện thoại đã ghép đôi |
| 04 | Khóa | Mọi điện thoại đã ghép đôi |
| 05 | Chế độ cập nhật (OTA) | Admin |
| 06 | Mở ghép đôi điện thoại mới | Admin |
| 07 | Thu hồi điện thoại | Admin |

Mã 05 đến 07 là đề xuất, có thể đổi khi viết firmware. Kết quả: `00` OK, `01` lệnh sai, `02` sai xác thực, `03` lỗi RF.

## 7. Các giai đoạn

### Giai đoạn 1: Khung firmware BLE

- GATT server, advertising 100–200ms, nhận lệnh và gọi lớp RF giả.
- **Đạt khi:** ghi lệnh Lên bằng nRF Connect thì Serial in đúng lệnh.

### Giai đoạn 2: Ghép đôi và xác thực

- Giữ nút BOOT 3 giây để mở ghép đôi trong 60 giây; điện thoại đầu tiên thành admin.
- Bonding, bảng khóa nhiều ô trong NVS, app lưu khóa trong Android Keystore.
- Kiểm tra HMAC, đổi nonce sau mỗi lệnh, giới hạn số lần sai, phân quyền admin và thường.
- **Đạt khi:** lệnh sai HMAC và gói phát lại bị từ chối; lệnh admin gửi bằng khóa thường bị từ chối.

### Giai đoạn 3: App Android

- Màn hình ghép đôi: quét theo service UUID, bond, lưu khóa.
- Màn hình chính: bốn nút và trạng thái kết nối; mở app là kết nối thẳng tới thiết bị đã lưu.
- **Đạt khi:** bấm nút trên app thì Serial của ESP in đúng lệnh.

### Giai đoạn 4: Tối ưu và xử lý lỗi

- Mục tiêu dưới 1 giây từ lúc mở app tới lúc bấm được.
- Tự kết nối lại; xử lý Bluetooth tắt, thiếu quyền, ESP khởi động lại.
- Thêm INFO với giá trị nguồn và pin giả; app hiện trạng thái nguồn và làm mờ nút khi mất điện.
- Tùy chọn: widget ngoài màn hình chính.

### Giai đoạn 5: OTA

- Lệnh bật chế độ cập nhật, nhập WiFi từ app, mục "Cập nhật firmware" trong phần cài đặt.
- **Đạt khi:** nạp được firmware mới qua WiFi không cần cắm cáp; hết 5 phút WiFi tự tắt.
- Nên xong trước khi lắp mạch cố định ở cửa.

### Giai đoạn 6: RF thật và nguồn (khi linh kiện về)

- Chế độ học mã: ESP thu từ remote và lưu vào NVS theo từng nút, điều khiển từ app admin.
- Thay lớp RF giả bằng rc-switch, phát lặp 10–15 lần mỗi lệnh.
- Lắp pin và cầu phân áp nhận biết có điện; thay giá trị giả của INFO bằng số đo thật.
- Thử tầm phát, chọn vị trí đặt mạch, đóng hộp.
- **Đạt khi:** bốn nút trên app điều khiển được cửa; rút adapter thì app báo mất điện trong vài giây.

### Giai đoạn 7: Thêm điện thoại (tương lai)

- Giao diện admin: thêm điện thoại, xem danh sách, đổi tên, thu hồi.

## 8. Ghi chú

- Cảm biến trạng thái cửa (công tắc từ ở đáy cửa) là tùy chọn. RF là một chiều nên không có cảm biến thì app không biết cửa đã chạy hay chưa.
- Mã của remote là mã cố định nên vốn có thể bị thu và phát lại. Phần BLE được xác thực để không mở thêm lỗ hổng.
- Giữ remote RF làm dự phòng. Khi mất điện chỉ mở được cửa bằng xích kéo tay.
