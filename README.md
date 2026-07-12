# Melon Terminal (Android)

FeliCa IDi ベースのオンライン前払式決済サーバ **melon** の、加盟店向け Android 決済端末アプリです。
NFC 対応の Android 端末を、支払い・チャージ・残高照会・返金ができる POS 端末にします。

デスクトップ版 `melon-terminal`(Rust)と同じ **リレー方式** を採用しています。カードとの
FeliCa 相互認証はすべて **サーバ側** で行われ、端末はカードとサーバの間でコマンドフレームを
中継するだけです。DES 秘密鍵は端末には一切保存されません。

---

## 動作の仕組み

```
  FeliCa カード  ⇄  Android 端末(このアプリ)  ⇄  melon-server
   (NFC-F)          ・NFC でフレームを中継          ・DES 鍵で相互認証を駆動
                    ・API キーで加盟店認証          ・検証済み IDi → account_id
                    ・残高は持たない                ・残高台帳(PostgreSQL)
```

1. カードをかざすと、アプリはワイルドカード(`0xFFFF`)でポーリングして IDm/PMm を取得し、
   `Request System Code` でカードの対応システムを問い合わせます。
2. サーバが認証できるシステムコード(`GET /v1/system-codes`)と突き合わせて 1 つ選び、その
   システムで再ポーリングします。
3. `POST /v1/mutual-authentication` を起点に、サーバが返すフレームをカードへ送り、カードの
   応答をサーバへ返す、という中継ループを認証完了まで繰り返します。
4. 認証が完了すると、サーバから **認証済みセッション(`session_id`)** と、この加盟店だけに
   割り当てられた **仮名 ID(`account_id`)** が返ります。生の IDi/IDm は端末には渡りません。
5. そのセッションで、選んだ操作(支払い/チャージ/残高/返金)を実行します。金銭操作には
   `Idempotency-Key` を付けて二重処理を防ぎます。

---

## 対応環境

- **Android 7.0(API 24)以上**、NFC(FeliCa / NFC-F)対応端末
- 決済対象のカードは **FeliCa Standard**(サーバが相互認証できるもの)

NFC 非対応・無効の端末では画面上部に警告が表示され、操作は実行されません。

---

## ビルド

Android Studio で開くか、コマンドラインでビルドします。

```bash
# デバッグ APK
./gradlew assembleDebug
# 端末へインストール(USB デバッグ有効時)
./gradlew installDebug
```

`local.properties` に Android SDK のパスが必要です(Android Studio が自動生成)。

```properties
sdk.dir=/path/to/Android/Sdk
```

### 技術スタック

| 項目 | 採用 |
|---|---|
| 言語 / UI | Kotlin + Jetpack Compose(Material 3) |
| 通信 | OkHttp + kotlinx.serialization |
| NFC | `NfcAdapter` フォアグラウンド Reader Mode(`FLAG_READER_NFC_F`) |
| 非同期 | Kotlin Coroutines |
| 最小 SDK / ターゲット | 24 / 36 |

---

## 使い方

### 初回設定

初回起動時は設定画面が表示されます。

- **サーバ URL** — 既定は `https://melon.unknowntech.jp`
- **API キー** — 加盟店に発行された API キー

「保存」を押すと `GET /v1/system-codes` でキーを検証し、正しければ端末内に保存します。
以降は自動で操作画面が開きます。設定は右上の「⚙ 設定」からいつでも変更できます。

### 操作

上部のタブで操作を選びます。

| 操作 | 金額入力 | 内容 |
|---|---|---|
| **支払い** | 必要 | 金額を入力してカードをかざすと課金します |
| **チャージ** | 必要 | 金額を入力してカードをかざすと入金します |
| **残高** | 不要 | カードをかざすと残高と有効期限別内訳を表示します |
| **返金** | 不要 | カードをかざすと返金可能な支払いの一覧を表示し、選んで返金します |

金額はテンキー(電卓配列)で入力します。**「支払い」「チャージ」は、金額を入力し画面
最下部の「支払う」「チャージする」ボタンを押してからカードをかざします**(誤ってカードに
触れて取引が成立するのを防ぐため)。ボタンを押すと「カードをかざしてください」の確認画面
になり、そこで初めてタップが受け付けられます(キャンセル可)。処理中はカードを離さないで
ください。

処理が完了すると結果画面が表示されます。**カードをかざしたままでも次の処理は始まりません**
(二重課金防止)。結果を確認して閉じると、次の操作を受け付けます。

### 加盟店情報

右上の「🏬 加盟店」から、この端末に紐づく加盟店の情報を表示します(`GET /v1/me`)。

- **精算残高** — 発行者からの受取額(マイナスは発行者への支払額)
- **決済手数料率 / 与信限度 / チャージ可能額(余力)**
- 加盟店コード・名称・状態・ID・登録日時

---

## セキュリティ

- **API キーは端末内のみに保存**されます(アプリ専用の `SharedPreferences`。OS のサンドボックスで
  他アプリから保護)。サーバ認証にのみ使用します。
- **DES 秘密鍵は端末に存在しません。** 相互認証はサーバが実行します。
- 端末が扱えるのは加盟店ごとの **仮名 `account_id`** だけで、生の IDi/IDm は取得できません。
- 通信は HTTPS を前提とします。

---

## ソース構成

```
app/src/main/java/jp/unknowntech/melonterminal/
  MainActivity.kt          単一 Activity。NFC Reader Mode の有効化と画面表示
  felica/Felica.kt         FeliCa レイヤ(ポーリング / RequestSystemCode / フレーム中継)
  nfc/CardSession.kt        1 タップ = 1 カードセッション(接続 → 実行 → 切断)
  net/
    Dto.kt                 API の JSON DTO(kotlinx.serialization)
    MelonClient.kt         melon-server クライアント(OkHttp)
  core/
    Settings.kt            サーバ URL / API キーの保存
    Models.kt              Op / エラー分類 / 表示用エラー
    CardFlow.kt            相互認証の中継フロー
  ui/
    TerminalViewModel.kt   画面状態と操作の実行
    TerminalScreen.kt      操作画面・テンキー・各種シート
    SettingsScreen.kt      サーバ URL / API キー設定
    Format.kt              金額 / ID / 日付の整形
```

FeliCa レイヤは `FeliCaDumper` を参考に、melon のリレー方式に必要な部分
(ポーリング・RequestSystemCode・フレーム中継)だけを自己完結で実装しています。
暗号処理はサーバ側にあるため、端末側には含まれません。
