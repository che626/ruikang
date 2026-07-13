# 织瑕智析

织瑕智析是一套面向纺织面料质检场景的 AI 视觉应用系统，覆盖图片质检、摄像头实时监控、质检记录、缺陷分析、模型管理与 AIGC 辅助质量报告。

## 技术栈

- 前端：Vue 3、Vite、TypeScript、Ant Design Vue
- 后端：Spring Boot 3、Java 17、MyBatis-Plus
- 推理：ONNX Runtime、OpenCV、`ruikang.onnx`
- 数据库：开发环境 H2，容器环境 MySQL 8.4
- 部署：Docker Compose、Nginx

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

类别顺序来自 [`backend/models/data.yaml`](backend/models/data.yaml)，后端启动时会据此同步缺陷字典。

## 本地开发

环境要求：Java 17、Maven 3.9+、Node.js 22+。

启动后端：

```powershell
cd backend
mvn spring-boot:run
```

开发环境默认使用 H2，服务地址为 `http://localhost:8081`。本地开发账号默认为 `admin / admin123`，可通过 `QC_ADMIN_USERNAME` 和 `QC_ADMIN_PASSWORD` 覆盖。

启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

前端地址为 `http://localhost:5173`，Vite 会将 `/api` 和 `/ws` 代理到后端。

登录成功后，后端会签发 8 小时有效的 HttpOnly 会话 Cookie。业务 API、结果图片和 WebSocket 握手均由后端统一校验，前端本地令牌只用于页面路由状态。

## Docker 部署

先创建本地环境变量文件并替换其中的示例密码：

```powershell
Copy-Item .env.example .env
```

然后构建并启动：

```powershell
docker compose up -d --build
```

容器说明：

- `ruikang-mysql`：MySQL 数据库，数据持久化到 `mysql/data`
- `ruikang-backend`：后端与 ONNX 推理服务，质检文件保存到 `uploads`
- `ruikang-frontend`：前端静态资源与容器内反向代理，对宿主机开放 `127.0.0.1:20080`

云服务器可将 [`deploy-nginx-ruikang.conf`](deploy-nginx-ruikang.conf) 放入宿主机 Nginx 配置目录，再代理到 `127.0.0.1:20080`。数据库初始化脚本只会在首次创建 MySQL 数据卷时执行；已有数据卷需要按需执行 `mysql/repair` 中的同步脚本。

## 配置说明

主要环境变量见 [`.env.example`](.env.example)：

- `MYSQL_ROOT_PASSWORD`：MySQL root 密码
- `MYSQL_PASSWORD`：应用数据库账号密码
- `QC_ADMIN_USERNAME`、`QC_ADMIN_PASSWORD`：系统登录账号与密码
- `DEEPSEEK_API_KEY`：DeepSeek API Key，留空时使用本地规则生成质量分析
- `DEEPSEEK_MODEL`：DeepSeek 模型名称

`.env`、数据库文件、上传文件、运行日志、IDE 配置与构建产物均已加入 `.gitignore`，请勿将真实密钥提交到仓库。

## 目录结构

```text
backend/                 Spring Boot 后端、ONNX 模型与 H2 初始化脚本
frontend/                Vue 3 前端
mysql/init/              MySQL 首次初始化脚本
mysql/repair/            已有数据库的人工同步脚本
docker-compose.yml       容器编排
deploy-nginx-ruikang.conf 宿主机 Nginx 站点配置
```
