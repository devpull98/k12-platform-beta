# Runbook triển khai: Ràng buộc bắt buộc lên Ingress/LB trước Gateway

**Trạng thái tại thời điểm viết (2026-09-08):** chưa có manifest K8s/LB nào trong repo này —
`docker-compose.dev.yml` là compose test cục bộ, Gateway nhận traffic trực tiếp, không qua
ingress/LB nào. File này ghi lại **ràng buộc bắt buộc** mà bất kỳ ingress/LB thật nào đứng trước
Gateway production đều phải thoả, để `plan.md` Task 6's AC "ràng buộc lên ingress phải ghi thành
văn bản" có một chỗ đứng thật thay vì chỉ nằm trong trí nhớ — đúng câu chữ
[system-architecture.md ADR-008](../architecture/system-architecture.md#adr-008)
đã ghi: *"phải nằm trong manifest chứ không phải trí nhớ"*.

Đây **không phải** manifest triển khai thật (chưa chọn ingress controller/LB cụ thể — Nginx
Ingress, Traefik, cloud LB (ALB/GCLB)... đều chưa quyết định, và ngân sách hạ tầng vẫn còn treo ở
`_context.md` §7.5). Đây là **danh sách yêu cầu** mà manifest thật, khi được viết, phải thoả mãn.

## Vì sao 2 ràng buộc này bắt buộc

Kiến trúc chốt TLS terminate hoàn toàn tại Ingress/LB, không tại Gateway pod
([ADR-008](../architecture/system-architecture.md#adr-008); cùng nội dung lặp lại ở §6.3 "TLS & Ràng buộc Ingress"):
54.000 kết nối dồn vào 15s nếu giải mã TLS tại pod sẽ nuốt sạch CPU Gateway (~360 handshake/s/pod).
Đánh đổi đó chỉ đúng nếu Ingress/LB thoả đúng 2 điều kiện sau — thiếu một trong hai thì kết nối WS
hợp lệ bị cắt giữa chừng, đúng loại lỗi im lặng khó debug nhất (client thấy mất kết nối ngẫu
nhiên, log Gateway hoàn toàn sạch vì Gateway không hề biết LB vừa cắt).

## 2 ràng buộc bắt buộc trên Ingress/LB

1. **Passthrough WebSocket Upgrade** — Ingress/LB phải chuyển tiếp nguyên vẹn header
   `Connection: Upgrade` / `Upgrade: websocket` của HTTP/1.1, không được buffer toàn bộ response
   trước khi trả về client (một số reverse proxy mặc định buffer response, phá vỡ WS upgrade).
2. **Idle Timeout > 30s** — phải **lớn hơn** chu kỳ heartbeat client gửi lên
   (`HEARTBEAT`, rate limit 2 lần/30s — xem `plan.md` Task 7, §5.6). Một idle timeout ngắn hơn 30s
   sẽ khiến LB tự cắt một kết nối WS hoàn toàn hợp lệ, đang có heartbeat đều đặn, chỉ vì hiểu nhầm
   là idle.

## Checklist trước khi đưa Ingress/LB thật vào production

- [ ] Ingress/LB đã bật passthrough WebSocket Upgrade — xác nhận bằng kết nối WS thật xuyên qua
      Ingress/LB đó tới một Gateway pod thật (không test tắt qua `docker-compose.dev.yml`, vì
      compose không có ingress nào cả — xem "Trạng thái" ở trên), giữ kết nối mở > 60s không rớt.
- [ ] Idle timeout của Ingress/LB **được ghi tường minh trong manifest** (không phải giá trị mặc
      định của vendor — nhiều LB mặc định 60s, một số mặc định thấp hơn 30s) và > 30s.
- [ ] Nếu vendor LB có timeout tính theo idle ở cả hai chiều (client→LB, LB→pod), cả hai đều phải
      > 30s — một chiều đúng, chiều kia sai vẫn cắt kết nối.
- [ ] Ngưỡng `max_handshake_per_sec` (admission control L1, §5.6) phải đo **tại Ingress**, không
      phải tại Gateway pod — Ingress là điểm hứng traffic đầu tiên, chặn PH-1 (load-test harness
      chưa dựng, xem `_context.md`).

## Ví dụ tham khảo (KHÔNG phải cấu hình đã chọn)

Nếu chọn Nginx Ingress Controller (phổ biến nhất cho K8s, chỉ để minh hoạ hình dạng annotation,
không phải quyết định đã chốt):

```yaml
# VÍ DỤ MINH HOẠ — chưa phải manifest thật, chưa chốt ingress controller nào.
metadata:
  annotations:
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
    nginx.ingress.kubernetes.io/websocket-services: "uni-websocket-gateway"
```

Con số `3600`s (1 giờ) chỉ là ví dụ rộng rãi hơn nhiều so với ngưỡng tối thiểu 30s — giá trị thật
nên khớp với thời lượng phiên chơi dài nhất dự kiến (§1.1: ca thi đấu 18h50–21h30, ~2h40), không
phải copy nguyên số ví dụ này.

## Tài liệu liên quan

- [`plan.md`](../work/NOJIRA-uni-p1-realtime-core/plan.md) Task 6 (AC "ràng buộc ingress").
- [`system-architecture.md`](../architecture/system-architecture.md) §6.3, §10.4, ADR-008.
- [`engine-scaling-freeze.md`](./engine-scaling-freeze.md) — runbook khác cùng thư mục, cùng lý do
  tồn tại: ràng buộc vận hành phải ghi thành văn bản, không phải trí nhớ.
