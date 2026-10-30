<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { message } from "ant-design-vue";
import { CameraOutlined, PoweroffOutlined, ReloadOutlined, SettingOutlined } from "@ant-design/icons-vue";
import {
  fetchCameras,
  fetchDefectTypes,
  fetchModels,
  setModelActive,
  updateDefectTypeColor,
  type CameraItem,
  type DefectTypeItem,
  type ModelItem,
} from "@/api/catalog";
import { useRealtimeStream } from "@/composables/useRealtimeStream";

const route = useRoute();
const stream = useRealtimeStream();

const defectTypes = ref<DefectTypeItem[]>([]);
const models = ref<ModelItem[]>([]);
const cameras = ref<CameraItem[]>([]);
const browserCameras = ref<MediaDeviceInfo[]>([]);
const loading = ref(false);
const cameraLoading = ref(false);

const currentSection = computed(() => String(route.params.section ?? "defect"));
const sectionTitle = computed(() => {
  if (currentSection.value === "model") return "模型管理";
  if (currentSection.value === "camera") return "摄像头配置";
  return "缺陷类型";
});

async function loadSection() {
  loading.value = true;
  try {
    if (currentSection.value === "model") {
      models.value = await fetchModels();
    } else if (currentSection.value === "camera") {
      cameras.value = await fetchCameras();
      await loadBrowserCameras(false);
    } else {
      defectTypes.value = await fetchDefectTypes();
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : "加载配置失败");
  } finally {
    loading.value = false;
  }
}

async function changeDefectColor(record: DefectTypeItem, event: Event) {
  const color = (event.target as HTMLInputElement).value;
  try {
    defectTypes.value = await updateDefectTypeColor(record.defectCode, color);
    message.success("缺陷颜色已更新");
  } catch (error) {
    message.error(error instanceof Error ? error.message : "颜色更新失败");
  }
}

async function toggleModel(record: ModelItem) {
  try {
    const nextActive = record.isActive !== 1;
    models.value = await setModelActive(record.id, nextActive);
    message.success(nextActive ? "模型已启用" : "模型已停用");
  } catch (error) {
    message.error(error instanceof Error ? error.message : "模型状态更新失败");
  }
}

async function loadBrowserCameras(requestPermission = true) {
  if (!navigator.mediaDevices?.enumerateDevices) {
    browserCameras.value = [];
    return;
  }

  cameraLoading.value = true;
  let permissionStream: MediaStream | null = null;
  try {
    if (requestPermission) {
      permissionStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false });
    }
    const devices = await navigator.mediaDevices.enumerateDevices();
    browserCameras.value = devices.filter((device) => device.kind === "videoinput");
  } catch (error) {
    message.error(error instanceof Error ? error.message : "摄像头检测失败");
  } finally {
    permissionStream?.getTracks().forEach((track) => track.stop());
    cameraLoading.value = false;
  }
}

async function chooseBrowserCamera(deviceId: string) {
  await stream.setCameraDevice(deviceId);
  message.success("实时监控将使用该摄像头");
}

watch(
  () => route.params.section,
  loadSection,
);

onMounted(loadSection);
</script>

<template>
  <section class="card config-panel-page">
    <div class="card-head">
      <h3 class="section-heading"><SettingOutlined />{{ sectionTitle }}</h3>
      <a-button :loading="loading" @click="loadSection">
        <template #icon><ReloadOutlined /></template>
        刷新
      </a-button>
    </div>

    <a-table
      v-if="currentSection === 'defect'"
      :loading="loading"
      :pagination="false"
      :data-source="defectTypes"
      size="small"
      row-key="id"
      :scroll="{ x: 680 }"
    >
      <a-table-column title="编码" data-index="defectCode" key="defectCode" />
      <a-table-column title="名称" data-index="defectName" key="defectName" />
      <a-table-column title="英文名" data-index="defectNameEn" key="defectNameEn" />
      <a-table-column title="颜色" key="displayColor">
        <template #default="{ record }">
          <div class="color-editor">
            <input
              type="color"
              :value="record.displayColor"
              aria-label="选择缺陷颜色"
              @change="changeDefectColor(record, $event)"
            />
            <span>{{ record.displayColor }}</span>
          </div>
        </template>
      </a-table-column>
      <a-table-column title="排序" data-index="sortNo" key="sortNo" />
    </a-table>

    <a-table
      v-else-if="currentSection === 'model'"
      :loading="loading"
      :pagination="false"
      :data-source="models"
      size="small"
      row-key="id"
      :scroll="{ x: 1050 }"
    >
      <a-table-column title="模型名称" data-index="modelName" key="modelName" />
      <a-table-column title="版本" data-index="modelVersion" key="modelVersion" />
      <a-table-column title="路径" data-index="modelPath" key="modelPath" />
      <a-table-column title="输入尺寸" data-index="inputSize" key="inputSize" />
      <a-table-column title="使用状态" key="active">
        <template #default="{ record }">
          <a-tag :color="record.isActive === 1 ? 'success' : undefined">
            {{ record.isActive === 1 ? "使用中" : "已停用" }}
          </a-tag>
        </template>
      </a-table-column>
      <a-table-column title="操作" key="action">
        <template #default="{ record }">
          <a-button
            type="link"
            :danger="record.isActive === 1"
            size="small"
            @click="toggleModel(record)"
          >
            <template #icon><PoweroffOutlined /></template>
            {{ record.isActive === 1 ? "停用" : "启用" }}
          </a-button>
        </template>
      </a-table-column>
    </a-table>

    <template v-else>
      <div class="camera-picker">
        <div class="camera-picker-head">
          <div>
            <strong>本地摄像头</strong>
            <span>{{ browserCameras.length }} 个设备</span>
          </div>
          <a-button :loading="cameraLoading" @click="loadBrowserCameras(true)">
            <template #icon><ReloadOutlined /></template>
            检测设备
          </a-button>
        </div>

        <div v-if="browserCameras.length" class="camera-device-list">
          <button
            v-for="(device, index) in browserCameras"
            :key="device.deviceId"
            :class="['camera-device', { active: stream.selectedCameraDeviceId.value === device.deviceId }]"
            type="button"
            @click="chooseBrowserCamera(device.deviceId)"
          >
            <CameraOutlined class="camera-icon" />
            <div>
              <strong>{{ device.label || `摄像头 ${index + 1}` }}</strong>
              <span>{{ stream.selectedCameraDeviceId.value === device.deviceId ? "当前使用" : "选择设备" }}</span>
            </div>
          </button>
        </div>
        <div v-else class="upload-preview-empty">暂无本地摄像头，请检测设备</div>
      </div>

      <a-table :loading="loading" :pagination="false" :data-source="cameras" size="small" row-key="id" :scroll="{ x: 680 }">
        <a-table-column title="编码" data-index="cameraCode" key="cameraCode" />
        <a-table-column title="名称" data-index="cameraName" key="cameraName" />
        <a-table-column title="类型" data-index="cameraType" key="cameraType" />
        <a-table-column title="状态" key="status">
          <template #default="{ record }">
            <a-tag :color="record.status === 1 ? 'success' : undefined">
              {{ record.status === 1 ? "启用" : "停用" }}
            </a-tag>
          </template>
        </a-table-column>
        <a-table-column title="位置" data-index="locationDesc" key="locationDesc" />
      </a-table>
    </template>
  </section>
</template>

<style scoped>
.section-heading {
  display: flex;
  align-items: center;
  gap: 8px;
}

.section-heading .anticon {
  color: #909399;
  font-size: 15px;
}

.camera-picker {
  margin-bottom: 20px;
  padding-bottom: 20px;
  border-bottom: 1px solid #ebeef5;
}

.camera-picker-head,
.camera-picker-head > div {
  display: flex;
  align-items: center;
  gap: 12px;
}

.camera-picker-head {
  justify-content: space-between;
  margin-bottom: 14px;
}

.camera-picker-head strong {
  font-weight: 500;
}

.camera-picker-head span {
  color: #909399;
  font-size: 13px;
}

.camera-device {
  display: flex;
  align-items: center;
  gap: 12px;
  border-radius: 4px;
  background: #fff;
}

.camera-device.active {
  border-color: #409eff;
  background: #ecf5ff;
}

.camera-icon {
  color: #909399;
  font-size: 24px;
}

.camera-device div {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.camera-device strong {
  font-weight: 500;
}
</style>
