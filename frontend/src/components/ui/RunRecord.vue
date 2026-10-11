<template>
    <div>
        <!-- 筛选区域 -->
        <el-row :gutter="20" class="filter-bar">
            <el-col :span="2">
                <!-- 清空日志按钮 -->
                <el-button type="warning" @click="clearLogs">清空日志</el-button>
            </el-col>
            <el-col :span="8">
                <el-time-picker
                    v-model="filter.timeRange"
                    is-range
                    start-placeholder="开始时间"
                    end-placeholder="结束时间"
                    format="HH:mm"
                    clearable
                    style="width: 100%"
                />
            </el-col>
            <el-col :span="6">
                <el-select v-model="filter.level" placeholder="请选择日志级别" style="width: 100%">
                    <el-option label="全部" value=""></el-option>
                    <el-option label="错误" value="error"></el-option>
                    <el-option label="警告" value="warn"></el-option>
                    <el-option label="信息" value="info"></el-option>
                    <el-option label="调试" value="debug"></el-option>
                    <el-option label="追踪" value="trace"></el-option>
                </el-select>
            </el-col>
            <el-col :span="8">
                <el-input
                    v-model="filter.keyword"
                    placeholder="请输入日志内容"
                    clearable
                    style="width: 100%"
                />
            </el-col>
        </el-row>

        <!-- 日志表格 -->
        <el-table :data="logs" style="width: 100%;min-height: 440px">
            <el-table-column prop="timestamp" label="时间" width="120"></el-table-column>
            <el-table-column prop="level" label="级别" width="100">
                <template #default="scope">{{ getRecordLevelLabel(scope.row.level) }}</template>
            </el-table-column>
            <el-table-column prop="message" label="内容" min-width="520">
                <template #default="scope"><div class="log-message">{{ scope.row.message }}</div></template>
            </el-table-column>
            <template #empty>
                <el-empty description="暂无日志数据"/>
            </template>
        </el-table>

        <!-- 分页 -->
        <el-pagination
            @current-change="handlePageChange"
            :current-page="currentPage"
            :page-size="pageSize"
            :total="totalLogs"
            background
            layout="prev, pager, next"
        ></el-pagination>
    </div>
</template>

<script setup lang="ts">
import {ref, onMounted, onUnmounted, watch} from 'vue';
import {LogRecorder} from '../../logging/record';
import {filterLogs, LogFilter, readAllLogs} from '../../logging/queries';

const levelLabels: Record<string, string> = {
    error: '错误',
    warn: '警告',
    info: '信息',
    debug: '调试',
    trace: '追踪',
};

const getRecordLevelLabel = (level: string) => levelLabels[String(level || '').toLowerCase()] || '信息';

// 创建日志记录器实例
const logRecorder = new LogRecorder();

// 数据响应式绑定
const logs = ref([]); // 当前页面显示的日志数据
const currentPage = ref(1); // 当前页码
const pageSize = ref(10); // 每页显示条数
const totalLogs = ref(0); // 日志总条数

// 筛选条件
const filter = ref<LogFilter>({
    timeRange: [], // 时间范围，数组：[开始时间, 结束时间]
    level: '', // 日志级别
    keyword: '', // 日志内容关键字
});

// 获取当前页的日志
const fetchLogs = () => {
    const allLogs = filterLogs(readAllLogs(logRecorder), filter.value);

    // 分页
    totalLogs.value = allLogs.length;
    const startIndex = (currentPage.value - 1) * pageSize.value;
    logs.value = allLogs.slice(startIndex, startIndex + pageSize.value) as any;
};

// 处理页码变更
const handlePageChange = (page: number) => {
    currentPage.value = page;
    fetchLogs();
};

// 监听筛选条件变化，自动刷新日志列表
watch(filter, () => {
    currentPage.value = 1; // 筛选条件变化时重置为第一页
    fetchLogs();
}, {deep: true});

// 清空日志
const clearLogs = () => {
    logRecorder.clearLogs();
    fetchLogs();
};

let refreshTimer: number | null = null;

// 组件挂载时初始化获取一次日志
onMounted(() => {
    fetchLogs();
    refreshTimer = window.setInterval(fetchLogs, 500);
});

onUnmounted(() => {
    if (refreshTimer) {
        clearInterval(refreshTimer);
        refreshTimer = null;
    }
});
</script>

<style scoped>
.filter-bar {
    margin-bottom: 20px;
}

.log-message {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    word-break: break-word;
    line-height: 1.55;
    padding: 4px 0;
}
</style>
