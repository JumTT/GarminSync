# GarminSync

把 Garmin **国际区**的运动数据同步到**国区**的 Android APP。电脑上 [dailysync](https://github.com/jiyaofeng/dailysync) 脚本的手机端替代品：不依赖 GitHub Actions 定时跑，打开 App 点一下就传。

## 功能

- **双区账号管理**：国区 / 国际区独立登录，启动自动校验登录态；会话失效时用已存密码静默重登，仍失败才要求手动登录
- **安全存储**：账号密码与 OAuth 会话经 Android Keystore（AES-GCM）加密后存 SQLite，密钥不出安全硬件，且禁止参与云备份
- **去重上传**：以国际区 `activityId` 为唯一 ID 记录已同步数据，上传成功或服务器判定重复均落库，绝不重复上传
- **手动可控**：展示国际区最近 10 条活动，未上传的默认勾选；已上传的标注出来，勾选即强制重传
- **进度可视**：整体进度条 + 每条状态（排队 / 上传中 / 成功 / 重复 / 失败），上传期间屏幕常亮

## 使用

1. 从 [Releases](../../releases) 下载最新 APK 安装
2. 首次打开按提示依次登录**国区**和**国际区**账号
3. 勾选要同步的活动，点「上传所选」
4. 已上传过的活动想重传？手动勾选即可强制重传

> 国际区 `connect.garmin.com` 大陆直连不通，手机需要有系统级 VPN/代理；国区直连即可。

## 技术实现

Kotlin + Jetpack Compose + Room + OkHttp 原生实现，登录协议与
[@gooin/garmin-connect](https://www.npmjs.com/package/@gooin/garmin-connect)（[dailysync](https://github.com/jiyaofeng/dailysync) 所用库）完全对齐：

```
SSO 表单登录(3步) -> ticket -> OAuth1.0a preauthorize -> exchange OAuth2 Bearer
```

API 全部走 `connectapi.{garmin.cn|garmin.com}`，401 时用 OAuth1 令牌自动续期。MFA 未支持（登录两步验证的账号会失败）。

## 构建与发布

推 semver tag 自动打包并发布 Release：

```bash
git tag v1.2.3 && git push origin v1.2.3
```

- 版本号必须符合 `vMAJOR.MINOR.PATCH[-prerelease]`（semver），否则 CI 拒绝构建
- `versionCode = MAJOR*10000 + MINOR*100 + PATCH`，带 `-` 后缀的 tag 自动标记为 prerelease
- 也可在 Actions 页手动触发（workflow_dispatch），产出 artifact 不发 Release

## License

GPL-3.0（协议移植自 GPL-3.0 项目 dailysync）
