# ReceiptNotice - 收款通知推送

手机收到支付宝、微信、银联等收款通知时，自动将信息推送到你指定的服务器。支持 root 设备自动检测并拉起支付应用。

本项目基于 [WeihuaGu/receiptnotice](https://github.com/WeihuaGu/receiptnotice) 二次开发，升级构建工具链，新增 Python 服务端和 root 增强功能。

---

## 功能

- 监听手机通知，自动识别支付宝、微信、银联、银行短信等收款信息
- 提取金额、类型、标题等字段，以 JSON 格式 POST 到指定 URL
- 支持 DES 加密传输和 MD5 签名验证
- Root 设备：精确检测支付宝/微信进程状态，一键拉起未运行的应用
- 非 Root 设备：通过 UsageStatsManager 检测应用活跃状态
- 配套 Python 服务端，开箱即用

## 架构

```
手机 App (通知监听)  -->  POST JSON  -->  Python 服务端 (Flask)
                                              |
                                         SQLite 存储
                                              |
                                         Web 界面查看
```

## 快速开始

### 1. 服务端部署

```bash
cd server
pip install -r requirements.txt
python server.py --port 5000
```

服务启动后访问 `http://你的IP:5000` 查看收款记录。

**可选参数：**

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `--host` | 监听地址 | `0.0.0.0` |
| `--port` | 监听端口 | `5000` |
| `--secret` | DES 密钥 / MD5 签名密钥 | 空（不加密） |
| `--db` | SQLite 数据库路径 | `receipts.db` |

### 2. 安装 App

**[>>> 点击下载最新 APK <<<](https://github.com/giaoimgiao/receiptnotice/releases/latest/download/app-debug.apk)**

或前往 [Releases](https://github.com/giaoimgiao/receiptnotice/releases) 页面查看所有版本。

### 3. 配置 App

1. 打开 App，授予通知监听权限
2. 在主页填入推送地址：`http://你的服务器IP:5000/api/receive`
3. 点击「设置post地址」

### 4. （可选）加密配置

在 App 设置中：
- 勾选「加密」
- 选择加密方法（DES 或 MD5）
- 填写密钥

服务端启动时加上相同密钥：
```bash
python server.py --secret 你的密钥
```

## 推送的 JSON 格式

```json
{
  "type": "alipay",
  "time": "2026-03-04 14:30:00",
  "title": "支付宝收款",
  "money": "12.50",
  "content": "成功收款12.50元",
  "sign": "a1b2c3d4...",
  "deviceid": "device-xxx",
  "encrypt": "0"
}
```

| 字段 | 说明 |
|------|------|
| `type` | 支付类型：`alipay` / `wechat` / `unionpay` / `message-bank` 等 |
| `time` | 通知时间 |
| `title` | 通知标题 |
| `money` | 金额 |
| `content` | 通知内容 |
| `sign` | MD5 签名 |
| `deviceid` | 设备标识 |
| `encrypt` | `0`=明文 `1`=DES加密 |

## 服务端 API

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/receive` | POST | App 推送通知到此接口 |
| `/api/records` | GET | 查询收款记录（支持 `page`/`size` 参数） |
| `/` | GET | Web 管理界面 |

## 构建

项目使用 GitHub Actions 自动构建。每次推送到 `master` 分支会自动生成 APK。

手动构建：
```bash
./gradlew assembleDebug
```

构建环境要求：JDK 17、Android SDK Platform 28。

## 交流

QQ 群：[黑客(嘿客)](https://qun.qq.com/universal-share/share?ac=1&authKey=nke7h4EKjyCOhcfEUKykuj6tmORdgmuVMO5Vkyk9ixlQBjuPniCUlGtoJuKdmsQM&busi_data=eyJncm91cENvZGUiOiI4NzUyMzk2NzMiLCJ0b2tlbiI6Im9xdSs1YWo1cFk4ZTJhajVzU085Q3htMFpOUk5TWDhCelEyRVlVNHhWcWRtZnpaUUZnTEx0NnZVLzlQYWVzcjUiLCJ1aW4iOiIzNTI3MjI4ODE5In0%3D&data=5RRRdI4ugE4UsEY103k8JC4CtVVpyafxxZ_uOOgmZL3Ti9gnYItZbGveCGRgUwz7vg16MXsLTGWz5oT8hbpjog&svctype=4&tempid=h5_group_info)

## 致谢

本项目基于以下开源项目：

- [WeihuaGu/receiptnotice](https://github.com/WeihuaGu/receiptnotice) - 原始项目
- [WHD597312/NLservice](https://github.com/WHD597312/NLservice) - 通知监听基础
- [pedrovgs/Lynx](https://github.com/pedrovgs/Lynx) - 实时日志

## 许可

基于修改版 Apache 2.0 协议，仅供个人非商业用途。
