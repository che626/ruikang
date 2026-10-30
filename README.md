# 织瑕智析

织瑕智析是一套面向纺织面料质检场景的 AI 视觉应用系统，覆盖图片质检、摄像头实时监控、质检记录、人工复核、缺陷分析和 AIGC 辅助质量报告。

系统在浏览器中运行面料缺陷检测模型，由 Spring Boot 后端提供模型分发、业务处理、数据存储和报告生成服务。

## 主要功能

- **图片质检**：选择多张面料图片，在浏览器中逐张检测并绘制缺陷框；检测结果、原图和结果图分批提交后保存。
- **实时监控**：在浏览器中读取摄像头画面并执行推理，展示缺陷框、帧率和延迟；连续检测到缺陷后保存异常记录与证据图。
- **质检记录**：按图片质检、实时质检和复核状态查看记录，支持通过、不通过、漏报、误报四种人工判定。
- **专家评测**：按质检类型查看最新的 20 条记录，逐条判定正确或错误，按每条正确 1.75 分汇总得分。
- **缺陷分析**：基于质检记录生成 AIGC 质量分析报告；未配置 DeepSeek API Key 时使用本地规则生成报告。
- **系统管理**：查看模型、缺陷类型和摄像头配置，调整实时检测参数。

专家评测使用记录已有的复核状态，并非独立的固定样本批次；新增记录或修改复核状态会影响评测结果。

## 技术栈

- 前端：Vue 3、Vite、TypeScript、Ant Design Vue
- 浏览器推理：ONNX Runtime Web、Web Worker、WebGPU / WASM、YOLO26s 导出的 `ruikang.onnx`
- 后端：Spring Boot 3、Java 17、Spring JDBC
- 数据库：MySQL 8.0+（容器环境使用 MySQL 8.4）
- 部署：Docker Compose、Nginx

实时监控和图片质检均在浏览器中执行模型推理。后端负责模型分发、任务、记录、文件、异常、复核、分析和 AIGC 报告，不加载 ONNX Runtime、OpenCV、CUDA 或 cuDNN。

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

环境要求：Java 17+、Maven 3.9+、Node.js 22+、MySQL 8.0+，推荐使用支持 WebGPU 的 Chrome 或 Edge。首次运行前依次执行 `mysql/init/01-init-schema.sql` 和 `mysql/init/02-seed-data.sql`，创建并初始化 `ruikang_fabric_qc` 数据库。

启动后端：

```powershell
cd backend
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="<本机 MySQL 密码>"
mvn spring-boot:run
```

后端默认连接 `localhost:3306/ruikang_fabric_qc`，地址为 `http://localhost:8081`。本地默认账号为 `admin / admin123`，可通过 `QC_ADMIN_USERNAME` 和 `QC_ADMIN_PASSWORD` 覆盖；正式环境应设置强密码。

启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

前端地址为 `http://localhost:5173`。Vite 将 `/api` 代理到后端，并提供跨源隔离响应头。第一次质检需要下载约 38 MB 的模型并预热；后续是否重新下载取决于浏览器缓存和模型版本。

## 运行方式

- 图片质检：浏览器逐张推理、绘制结果图，每处理最多 4 张图片后分批提交原图、结果图、检测框和耗时。
- 实时质检：摄像头预览直接显示，Worker 以单帧背压方式推理；正常帧不上传，每秒同步一次指标，连续命中后才提交证据。
- 执行策略：优先请求高性能 WebGPU 适配器，创建推理会话并完成模型预热；GPU 不可用或会话创建、预热失败时，释放失败会话后回退 WASM。跨源隔离可用时 WASM 使用多线程，否则使用单线程。实际执行方式随推理结果传递，界面不展示执行后端的技术名称。
- 模型一致性：浏览器下载后校验 SHA-256，后端接收结果时再次校验模型版本、类别和坐标范围。

目标推理帧率可设置到 60 FPS，但实际帧率取决于摄像头、浏览器和客户端硬件，不等同于目标值。连续命中按是否检测到缺陷计数，不要求每帧为同一类别。

批量选择图片不设固定张数上限，但仍受浏览器内存、单个文件大小及上传请求大小限制。后端默认单文件不超过 20 MB、单次请求不超过 200 MB；Docker 内 Nginx 的请求体上限为 30 MB。

## 测试与构建

```powershell
cd frontend
npm test
npm run build

cd ../backend
mvn test
```

浏览器推理重构的需求、技术方案、接口与验收记录位于 [`docs/browser-inference`](docs/browser-inference)。

## 界面与推理更新

- 恢复浅色后台界面、蓝色导航、顶部工具栏与页面页签，以及图片质检、实时质检、记录、分析和配置页的展示布局。
- 图片结果支持聚焦缺陷与完整图片两种查看方式；保留新版浏览器推理、分批保存和四种人工复核结论。
- 修复实时质检页面缺少视频引用导致启动失败的问题。
- 隐藏模型管理的资源策略列，模型加载按钮只显示“正在加载模型”“模型已就绪”。隐藏文案不改变实际执行方式。
- 增加 GPU 成功、无 GPU、GPU 创建失败、GPU 预热失败及双方均失败的会话选择测试。

更新前端后，请刷新浏览器，让已有页面重新创建推理 Worker。首次预热时间与后续单次推理耗时不同，实际速度取决于模型、浏览器和设备。

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

Compose 为 MySQL、后端和前端分别设置了 700 MB、700 MB 和 128 MB 的内存上限，以及 1、1 和 0.5 个 CPU 的使用上限。

云服务器不需要 NVIDIA GPU、CUDA 或 cuDNN，推理算力来自访问系统的客户端电脑。正式环境必须使用 HTTPS，摄像头和 WebGPU 才能稳定启用。

宿主机可使用 [`deploy-nginx-ruikang.conf`](deploy-nginx-ruikang.conf) 代理到 `127.0.0.1:20080`。数据库初始化脚本只在首次创建 MySQL 数据卷时执行；已有数据库需执行 [`mysql/repair/2026-07-13-browser-inference.sql`](mysql/repair/2026-07-13-browser-inference.sql)。

## 配置与安全

主要环境变量见 [`.env.example`](.env.example)：

- `MYSQL_ROOT_PASSWORD`：MySQL root 密码
- `MYSQL_PASSWORD`：应用数据库账号密码
- `QC_ADMIN_USERNAME`、`QC_ADMIN_PASSWORD`：系统登录账号与密码
- `DEEPSEEK_API_KEY`：DeepSeek API Key，留空时使用本地规则生成质量分析；接口调用异常时是否回退由后端配置决定
- `DEEPSEEK_MODEL`：DeepSeek 模型名称

模型会下载到已登录用户的浏览器，因此该架构不提供模型文件保密能力。客户端检测结果会经过后端业务校验，但不会再由后端执行第二次模型推理。

`.env`、`.env.local`、`.local` 本机工具/数据目录、数据库文件、上传文件、运行日志、IDE 配置与构建产物均已加入 `.gitignore`，请勿提交真实密钥。

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
