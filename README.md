# 织瑕智析

织瑕智析是一套面向纺织面料质检场景的 AI 视觉应用系统，覆盖图片质检、摄像头实时监控、质检记录、缺陷分析、模型管理与 AIGC 辅助质量报告。

## 技术栈

- 前端：Vue 3、Vite、TypeScript、Ant Design Vue
- 浏览器推理：ONNX Runtime Web、Web Worker、WebGPU / WASM、`ruikang.onnx`
- 后端：Spring Boot 3、Java 17、Spring JDBC
- 数据库：MySQL 8.0+（容器环境使用 MySQL 8.4）
- 部署：Docker Compose、Nginx

实时监控和图片质检均在浏览器中执行模型推理。后端只负责模型分发、任务、记录、文件、异常、复核、分析和 AIGC 报告，不加载 ONNX Runtime、OpenCV、CUDA 或 cuDNN。

## 模型类别

当前模型支持 6 类缺陷：

| 索引 | 代码 | 中文名称 |
| --- | --- | --- |
| 0 | `oil_stain` | 油渍 |
| 1 | `ink_stain` | 墨渍 |
| 2 | `hole` | 破洞 |
| 3 | `broken_yarn` | 断纱 |
| 4 | `wrinkle` | 褶皱 |
| 5 | `foreign_matter` | 异物 |

类别顺序来自 [`backend/models/data.yaml`](backend/models/data.yaml)。后端启动时同步缺陷字典，并通过模型清单将同一顺序交给浏览器。

## 本地开发

环境要求：Java 17+、Maven 3.9+、Node.js 22+、MySQL 8.0+，推荐最新版 Chrome 或 Edge。首次运行前依次执行 `mysql/init/01-init-schema.sql` 和 `mysql/init/02-seed-data.sql`。

启动后端：

```powershell
cd backend
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="<本机 MySQL 密码>"
mvn spring-boot:run
```

后端默认连接 `localhost:3306/ruikang_fabric_qc`，地址为 `http://localhost:8081`。默认账号为 `admin / admin123`，可通过 `QC_ADMIN_USERNAME` 和 `QC_ADMIN_PASSWORD` 覆盖。

启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

前端地址为 `http://localhost:5173`。Vite 将 `/api` 代理到后端，并为多线程 WASM 提供跨源隔离响应头。第一次质检会下载约 38 MB 的模型并预热；浏览器缓存生效后不会在每次操作时重新下载。

## 运行方式

- 图片质检：浏览器逐张推理、绘制结果图，再一次性提交原图、结果图、检测框和耗时。
- 实时质检：摄像头预览直接显示，Worker 以单帧背压方式推理；正常帧不上传，每秒同步一次指标，连续命中后才提交证据。
- 执行策略：优先 WebGPU，GPU 不可用或初始化失败时自动回退多线程 WASM。
- 模型一致性：浏览器下载后校验 SHA-256，后端接收结果时再次校验模型版本、类别和坐标范围。

## 测试与构建

```powershell
cd frontend
npm test
npm run build

cd ../backend
mvn test
```

浏览器推理重构的需求、技术方案、接口与验收记录位于 [`docs/browser-inference`](docs/browser-inference)。

## Docker 部署

先创建 `.env` 并替换示例密码：

```powershell
Copy-Item .env.example .env
```

然后构建并启动：

```powershell
docker compose up -d --build
```

容器说明：

- `ruikang-mysql`：MySQL 数据库，数据持久化到 `mysql/data`
- `ruikang-backend`：业务后端，模型只作为静态资产分发，质检文件保存到 `uploads`
- `ruikang-frontend`：前端静态资源、浏览器推理运行时与容器内反向代理，对宿主机开放 `127.0.0.1:20080`

云服务器不需要 NVIDIA GPU、CUDA 或 cuDNN，推理算力来自访问系统的客户端电脑。正式环境必须使用 HTTPS，摄像头和 WebGPU 才能稳定启用。

宿主机可使用 [`deploy-nginx-ruikang.conf`](deploy-nginx-ruikang.conf) 代理到 `127.0.0.1:20080`。数据库初始化脚本只在首次创建 MySQL 数据卷时执行；已有数据库需执行 [`mysql/repair/2026-07-13-browser-inference.sql`](mysql/repair/2026-07-13-browser-inference.sql)。

## 配置与安全

主要环境变量见 [`.env.example`](.env.example)：

- `MYSQL_ROOT_PASSWORD`：MySQL root 密码
- `MYSQL_PASSWORD`：应用数据库账号密码
- `QC_ADMIN_USERNAME`、`QC_ADMIN_PASSWORD`：系统登录账号与密码
- `DEEPSEEK_API_KEY`：DeepSeek API Key，留空时使用本地规则生成质量分析
- `DEEPSEEK_MODEL`：DeepSeek 模型名称

模型会下载到已登录用户的浏览器，因此该架构不提供模型文件保密能力。客户端检测结果会经过后端业务校验，但不会再由后端执行第二次模型推理。

`.env`、数据库文件、上传文件、运行日志、IDE 配置与构建产物均已加入 `.gitignore`，请勿提交真实密钥。

## 目录结构

```text
backend/                  Spring Boot 业务后端与模型资产
frontend/                 Vue 3 页面与浏览器推理 Worker
docs/browser-inference/   本次架构重构的独立工程文档
mysql/init/               MySQL 首次初始化脚本
mysql/repair/             已有数据库升级脚本
docker-compose.yml        容器编排
deploy-nginx-ruikang.conf 宿主机 Nginx 站点配置
```
