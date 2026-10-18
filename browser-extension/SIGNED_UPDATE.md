# FloatingTranslator 固定签名 CRX 更新

v1.3 起，Chromium 包使用固定 RSA 公钥和固定 `update_url`。只要后续 CRX 始终使用同一把私钥签名，扩展 ID 就不会再变化。

- 固定扩展 ID：`hlfnagdelcpfdbpdeackdjelemaaoban`
- 更新清单：`https://raw.githubusercontent.com/dulo11/fanyireal-time/extension-update-channel/updates.xml`
- 稳定 CRX 地址：`https://github.com/dulo11/fanyireal-time/releases/latest/download/FloatingTranslator-Chromium.crx`

## 私钥

私钥绝不能提交到 GitHub。仓库 `.gitignore` 已排除常见 PEM/KEY 文件。

发布工作流读取仓库 Secret：

`FT_CRX_PRIVATE_KEY_B64`

值应为固定 PEM 私钥文件的 Base64 单行文本。只需配置一次。以后所有正式 CRX 都由 GitHub Actions 使用这把私钥签名。

## 发布

工作流：`.github/workflows/publish-signed-crx.yml`

可手动运行，也可推送 `browser-v*` Tag。它会：

1. 验证 Manifest 公钥对应的扩展 ID。
2. 从 `FT_CRX_PRIVATE_KEY_B64` 恢复临时 PEM 私钥。
3. 生成 CRX3 固定签名包和 ZIP 备用包。
4. 创建/更新 GitHub Release，并上传固定文件名 `FloatingTranslator-Chromium.crx`。
5. 生成 `updates.xml`。
6. 自动把 `updates.xml` 推到永久分支 `extension-update-channel`。
7. 生成 SHA-256 校验文件。

## 第一次迁移

v1.0 / v1.2 之前的 ZIP 安装没有固定签名，因此浏览器可能给它们分配不同 ID。第一次切到 v1.3 固定签名版时仍会被视为一个新扩展，这是最后一次。

建议迁移顺序：

1. 在旧扩展高级设置里“导出设置 JSON”。
2. 安装 v1.3 固定签名 CRX。
3. 在 v1.3 中导入刚才的设置 JSON。
4. 确认 Key、术语表和规则正常后，删除旧 ID 的扩展。

从此以后，只要继续使用同一私钥签名，v1.4、v1.5、v2.0 都保持 ID `hlfnagdelcpfdbpdeackdjelemaaoban`。

## 更新行为

支持标准 Chromium 自托管更新机制的浏览器会定期读取 Manifest 的 `update_url`。Popup 也提供“检查更新”按钮调用 `runtime.requestUpdateCheck()`。

不同 Chromium 浏览器对第三方 CRX 安装/自托管更新的限制并不完全相同。Google Chrome 官方对非商店自托管安装有限制；Quetta 等允许从 ZIP/CRX 安装的浏览器仍需实际验证它是否同时支持 `update_url` 自动更新。如果浏览器只支持手动更新，固定签名仍能保证覆盖更新时 ID 和设置不变。
