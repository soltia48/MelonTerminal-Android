# Melon Terminal ⇄ POS 連動プロトコル

POSレジ等の上位機器が、同一LAN上の Melon決済端末へコマンドを送り、
支払 / チャージ / 残高照会 / 返金を実行させるための通信仕様です。

## モデル

- **端末検索** … UDP ブロードキャストで端末の IP と TCP ポートを取得。
- **コマンド** … TCP + 改行区切りJSON。
- **状態取得** … Polling形式。取引開始後は `status` を繰り返し送り、終了状態になるまで待つ。
- 端末は同時に1取引だけを扱う。取引の全状態は `status` レスポンスに集約される。


## トランスポート

| 項目 | 値 |
|---|---|
| 端末検索 UDP ポート | `65024` |
| コマンド TCP ポート | `65025` |
| 文字コード | UTF-8 |
| フレーミング | `\n` 終端のJSON |
| プロトコルバージョン | `1`(フレームの `melon_pos`値) |

## メッセージ形式

UDP・TCP とも、全パケットを次のフレームで包む。リクエストは `msg.command`、レスポンスは
`msg.type` を持つ。キー順は不定。

```json
{ "melon_pos": 1, "alg": "none", "sig": null, "msg": { ... } }
```

| フィールド | 型 | 説明 |
|---|---|---|
| `melon_pos` | int | プロトコルバージョン 兼 マジック。`1` 以外は拒否。 |
| `alg` | string | RFU。現状 `"none"` 固定(他値は `UNSUPPORTED_ALG`)。 |
| `sig` | string \| null | RFU。現状 `null`。 |
| `msg` | object | 実体オブジェクト。 |

応答フレームでは値が `null` のフィールド(`sig` など)は省略される。

## 端末検索

POS がブロードキャストへ `discover` を送り、端末がユニキャストで `announce` を返す。

```json
// → 255.255.255.255:65024
{ "melon_pos": 1, "alg": "none", "msg": { "command": "discover" } }

// ← 端末
{ "melon_pos": 1, "alg": "none", "msg": {
  "type": "announce", "terminal_id": "3f2b…", "name": "Melon 端末",
  "ip": "192.168.0.12", "tcp_port": 65025, "app_version": "0.1.5", "state": "idle"
} }
```

`ip` / `tcp_port` で TCP 接続を張る。`terminal_id` は端末ごとに一意。

## コマンド

`amount` は円単位の整数。`request_id` は任意だが、付与を推奨(同一
`request_id` の再送は冪等になる)。`request_id` はPOSが決める任意文字列。

| command | 業務 | カード | パラメータ |
|---|---|---|---|
| `info` | — | — | なし |
| `payment` | 支払 | 要 | `amount`(必須), `note`, `request_id` |
| `topup` | チャージ | 要 | `amount`(必須), `request_id` |
| `balance` | 残高照会 | 要 | `request_id` |
| `refund_query` | 返金照会 | 要 | `request_id` |
| `refund_execute` | 返金 | 不要 | `payment_id`(必須), `amount`, `request_id` |
| `status` | 状態取得 | — | なし |
| `cancel` | キャンセル | — | `request_id` |

```json
{ "melon_pos":1, "alg":"none", "msg":{ "command":"payment", "amount":1234, "note":"お弁当", "request_id":"r-001" } }
{ "melon_pos":1, "alg":"none", "msg":{ "command":"refund_execute", "payment_id":"019f7893-b275-7961-9c23-5f2bf4e74d28", "amount":500, "request_id":"r-004" } }
{ "melon_pos":1, "alg":"none", "msg":{ "command":"status" } }
```

## status レスポンス

`payment` / `topup` / `balance` / `refund_query` / `refund_execute` / `status` / `cancel` は
すべて同じ `status` を返す。POSはこれをPollingする。

```json
{
  "type": "status",
  "transaction_id": "98765ab…",
  "request_id": "12345ab…",
  "job": "payment",
  "state": "waiting_card",
  "status_text": "カードをかざしてください",
  "amount": 1234,
  "refundable": null,
  "updated_at": 1752480000000,
  "result": null
}
```

| フィールド | 説明 |
|---|---|
| `transaction_id` | 取引 ID(`idle` のとき null)。 |
| `request_id` | 取引を開始したコマンドの `request_id`。 |
| `job` | `payment` / `topup` / `balance` / `refund_query` / `refund` / null。 |
| `state` | 下表。 |
| `status_text` | 端末表示中の日本語ステータス。 |
| `amount` | 金額(該当業務のみ)。 |
| `refundable` | `refund_query` 成功時のみ、返金可能な取引の配列。 |
| `updated_at` | 端末側の更新時刻(Unix ミリ秒)。 |
| `result` | 終了状態でのみ設定(下記)。 |

### state

| state | 意味 | 端末画面 |
|---|---|---|
| `idle` | 取引なし | 待受中 |
| `pending` | 準備中(サーバ接続など) | 「お待ちください」 |
| `waiting_card` | カード待受 | 「カードをかざしてください」・青色点滅 |
| `processing` | 処理中 | 「処理中…」・青色点滅(カード必要業務のみ) |
| `success` | 成功(終了) | 5 秒 青色 |
| `failed` | 失敗(終了) | 5 秒 赤色 |
| `cancelled` | キャンセル(終了) | 「キャンセルしました」 |

`success` / `failed` / `cancelled` が終了状態。POS はこの 3 状態になるまで Polling する。
端末の結果表示は 5 秒続くが、`result` は完了と同時に返されるので待つ必要はない。

## result(終了状態のみ)


```json
// 支払
{ "ok": true, "account_id": "67995d5d-…", "amount": 1234, "fee": 12, "balance": 5000 }
// チャージ(expires_at は無ければ省略)
{ "ok": true, "account_id": "67995d5d-…", "amount": 3000, "balance": 8000, "expires_at": "2027-03-31" }
// 残高照会
{ "ok": true, "account_id": "67995d5d-…", "total": 5000,
  "buckets": [ { "bucket_id": "d0721d5c…", "remaining": 5000, "expires_at": "2027-03-31" } ] }
// 返金照会(一覧は status.refundable)
{ "ok": true, "account_id": "67995d5d-…" }
// 返金(カード非経由のため account_id は返さない)
{ "ok": true, "amount": 500, "balance": 4500 }
// 失敗(全業務共通)
{ "ok": false, "code": "INSUFFICIENT_FUNDS", "title": "残高が不足しています", "detail": "…" }
```

## 返金
`refund_query` と `refund_execute` は独立している。

- **`refund_query`**
  カードを読み、返金可能な取引の一覧を `status.refundable` で返す(カード業務、成功で終了)。
  各要素:`{ "payment_id", "amount", "fee", "refunded", "refundable", "occurred_at" }`
- **`refund_execute`**
  `payment_id`を指定して返金処理を実行する。`amount` 省略で全額。

## キャンセル

`cancel` コマンド、または端末の「キャンセル」ボタン。取り消せるのは `pending` /
`waiting_card` のみで、成功すると `cancelled`(終了状態)になる。

`processing` 以降の `cancel` は無視される(POSとの取引アンマッチ防止のため)。この場合は取引を変えず、現在の `status` をそのまま返す。端末側もこの間はキャンセルボタンを表示しない。

## エラー

受理できないコマンドは `status` ではなく `error` を返す。

```json
{ "melon_pos": 1, "alg": "none", "msg": { "type": "error", "code": "BUSY", "message": "…" } }
```

| code | 意味 |
|---|---|
| `BAD_REQUEST` | JSON 不正・必須不足・値が範囲外 |
| `UNKNOWN_COMMAND` | 未知の `command` |
| `UNSUPPORTED_VERSION` | `melon_pos` が非対応 |
| `UNSUPPORTED_ALG` | `alg` が非対応 |
| `NOT_CONFIGURED` | 端末に API キー未設定 |
| `BUSY` | 別の取引が進行中 |

同一 `request_id` かつ同一 `job` で進行中の取引に再送した場合は、`BUSY` ではなく現在の`status` を返す(冪等性を持たせるため)。

## info

```json
// → { "command": "info" }
// ←
{ "type": "info", "protocol": 1, "terminal_id": "3f2b…", "name": "Melon 端末",
  "app_version": "0.1.5", "jobs": ["payment","topup","balance","refund_query","refund_execute"] }
```

`app_version` は端末アプリの実バージョン。プロトコルバージョンは `protocol`(= フレームの `melon_pos`)。

## 通信例
### 状態取得(Polling)

```
send {"melon_pos":1,"alg":"none","sig":null,"msg":{"command":"status"}}
recv {"melon_pos":1,"alg":"none","msg":{"type":"status","transaction_id":"64f6b140-8709-4688-818b-457ad12d9230","request_id":"270622be-269e-480e-bdea-b2cd4843f3ba","job":"refund_query","state":"processing","status_text":"処理中…","updated_at":1784434535886}}
```

### 返金照会の完了(refundable つき)

```
recv {"melon_pos":1,"alg":"none","msg":{"type":"status","transaction_id":"64f6b140-8709-4688-818b-457ad12d9230","request_id":"270622be-269e-480e-bdea-b2cd4843f3ba","job":"refund_query","state":"success","status_text":"照会完了しました","refundable":[{"payment_id":"019f7893-b275-7961-9c23-5f2bf4e74d28","amount":1,"fee":0,"refunded":0,"refundable":1,"occurred_at":"2026-07-19T04:12:53.237087Z"},{"payment_id":"019f70eb-35d5-7760-b81a-0965974c2e36","amount":1,"fee":0,"refunded":0,"refundable":1,"occurred_at":"2026-07-17T16:31:30.772797Z"}],"updated_at":1784434537503,"result":{"ok":true,"account_id":"67995d5d-fa5f-4915-b399-d3ca02751142"}}}
```

### 支払の完了

```
recv {"melon_pos":1,"alg":"none","msg":{"type":"status","transaction_id":"da11215a-4f75-402e-874a-6db59e95b902","request_id":"5b4636d1-79b5-4c54-aa29-80f4392d4548","job":"payment","state":"success","status_text":"支払完了","amount":1,"updated_at":1784434374039,"result":{"ok":true,"account_id":"67995d5d-fa5f-4915-b399-d3ca02751142","amount":1,"fee":0,"balance":1}}}
```

### チャージの完了

```
recv {"melon_pos":1,"alg":"none","msg":{"type":"status","transaction_id":"4ee68c3d-352f-42aa-9961-caa22cb5e27f","request_id":"8fcf8993-4163-49ba-8349-6cc8c3ab7799","job":"topup","state":"success","status_text":"チャージ完了","amount":1,"updated_at":1784436180053,"result":{"ok":true,"account_id":"67995d5d-fa5f-4915-b399-d3ca02751142","amount":1,"balance":3,"expires_at":"2027-01-18T15:00:00Z"}}}
```

### 残高照会の完了

```
recv {"melon_pos":1,"alg":"none","msg":{"type":"status","transaction_id":"8835d511-b944-47f0-973a-8c266052ce9d","request_id":"d6c6f161-55f8-4f9a-93b6-93022504775d","job":"balance","state":"success","status_text":"残高照会完了","updated_at":1784436090463,"result":{"ok":true,"account_id":"67995d5d-fa5f-4915-b399-d3ca02751142","total":1,"buckets":[{"bucket_id":"019f613a-54e5-7993-802f-d672646680ce","remaining":1,"expires_at":"2027-01-14T15:00:00Z"}]}}}
```

### 返金の完了

```
recv {"melon_pos":1,"alg":"none","msg":{"type":"status","transaction_id":"54e10948-8150-4b98-a176-a37aae612887","request_id":"bf8ea8b5-0588-4d60-a6aa-4165e6bc0697","job":"refund","state":"success","status_text":"返金完了","updated_at":1784436127412,"result":{"ok":true,"amount":1,"balance":2}}}
```
