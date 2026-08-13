# CoveFit Shell

CoveFit 三端架构中的**手机端 WebView 壳**：在应用进程内起一个本地转发代理（`LocalProxy`），按域名分流——仅 `*.workers.dev` 走 v2rayNG（`:10808`），其余直连；再通过 `ProxyController.setProxyOverride()` 设置**进程级**代理，从而：

- 不影响手机上其它 App 的网络（零系统全局代理）；
- 路径 A（Tailscale 私网直连 PC `:8788`）与路径 B（经 Cloudflare Worker 的控制面）互不干扰；
- 随 App 启动，无需在手机上手动切 v2rayNG / PAC。

PWA 本身由 `apps/cloud` 部署在 Cloudflare Workers 上，本仓库只承载这个原生壳。

## 构建（GitHub Actions，公开仓库无限分钟）

本仓库配置了 `.github/workflows/build.yml`：

- 触发：`workflow_dispatch`（手动）或推送到 `main`；
- 产物：`covefit-shell-debug` artifact（debug APK）。

操作：push 后在仓库 **Actions → Build Debug APK → Run workflow**，跑完到 Artifacts 下载 `app-debug.apk`，再用 `adb install app-debug.apk` 装到手机验证。

## 本地构建（可选）

需要 JDK 17 + Android SDK（platform-34 / build-tools 34.0.0）：

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 关键参数

| 项 | 值 |
|---|---|
| applicationId | `com.covefit.shell` |
| minSdk / targetSdk | 24 / 34 |
| 本地代理端口 | `127.0.0.1:8899` |
| 分流规则 | `*.workers.dev` → v2rayNG `127.0.0.1:10808`，其余 DIRECT |
| PWA 入口 | `https://covefit-tri-cloud.g-vampirenails.workers.dev/` |
