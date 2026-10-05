package com.electronics.assembly.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.electronics.assembly.AbstractIntegrationTest;
import com.electronics.assembly.entity.WorkOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 旧接口响应兼容 + 新闭环接口 HTTP 语义测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairApiCompatibilityTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void legacy_and_new_endpoints_keep_response_contract_and_idempotent_replay() throws Exception {
        WorkOrder order = newOrderWithSteps("WO-API-1");

        // 1) POST /api/inspections：旧响应结构 {ok,message,data:{id,verdict,repairDone,orderNo...}}
        String payload = json.writeValueAsString(java.util.Map.of(
                "order", java.util.Map.of("id", order.getId()),
                "stepName", "AOI",
                "defectType", "偏移",
                "defectPoints", 5,
                "sampleQty", 40,
                "inspector", "孙悦",
                "requestId", UUID.randomUUID().toString()));
        MvcResult r1 = mockMvc.perform(post("/api/inspections")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.verdict").value("返修"))
                .andExpect(jsonPath("$.data.repairDone").value(false))
                .andExpect(jsonPath("$.data.orderNo").value("WO-API-1"))
                .andReturn();
        long inspectionId = json.readTree(r1.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 2) 工序游标已置返修中
        mockMvc.perform(get("/api/smt-steps").param("orderId", String.valueOf(order.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[?(@.stepName=='AOI')].status")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is("返修中"))));

        // 3) 返修任务列表 / by-inspection
        MvcResult rt = mockMvc.perform(get("/api/repair-tasks/by-inspection/" + inspectionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roundNo").value(1))
                .andExpect(jsonPath("$.data.status").value("待返修"))
                .andReturn();
        long taskId = json.readTree(rt.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 4) 旧接口 POST /inspections/{id}/repair 空体仍可调，返回的仍是 Inspection
        mockMvc.perform(post("/api/inspections/" + inspectionId + "/repair"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.id").value(inspectionId))
                .andExpect(jsonPath("$.data.verdict").value("返修"));

        // 5) 返修工自己不能闭环：任务停在待复验，工序仍返修中
        mockMvc.perform(get("/api/repair-tasks/" + taskId))
                .andExpect(jsonPath("$.data.task.status").value("待复验"));

        // 6) 复验缺质检员 → 400 中文提示
        mockMvc.perform(post("/api/repair-tasks/" + taskId + "/recheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"合格\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("质检员")));

        // 7) 复验通过 → 闭环、工序已完成
        mockMvc.perform(post("/api/repair-tasks/" + taskId + "/recheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inspector\":\"孙悦\",\"result\":\"合格\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("已复验通过"));
        mockMvc.perform(get("/api/smt-steps").param("orderId", String.valueOf(order.getId())))
                .andExpect(jsonPath("$.data[?(@.stepName=='AOI')].status")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is("已完成"))));

        // 8) 旧 blocking 响应字段不变且已放行
        mockMvc.perform(get("/api/inspections/blocking/" + order.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(order.getId()))
                .andExpect(jsonPath("$.data.scrapped").exists())
                .andExpect(jsonPath("$.data.openRepairs").value(0))
                .andExpect(jsonPath("$.data.blocked").value(false));

        // 9) open-repairs 旧口径接口仍在
        mockMvc.perform(get("/api/inspections/open-repairs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void http_replay_with_same_request_id_returns_same_inspection() throws Exception {
        WorkOrder order = newOrderWithSteps("WO-API-2");
        String rid = UUID.randomUUID().toString();
        String payload = json.writeValueAsString(java.util.Map.of(
                "order", java.util.Map.of("id", order.getId()),
                "stepName", "AOI", "defectType", "虚焊",
                "defectPoints", 3, "sampleQty", 40,
                "inspector", "孙悦", "requestId", rid));

        MvcResult a = mockMvc.perform(post("/api/inspections")
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn();
        MvcResult b = mockMvc.perform(post("/api/inspections")
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn();
        JsonNode na = json.readTree(a.getResponse().getContentAsString());
        JsonNode nb = json.readTree(b.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(nb.path("data").path("id").asLong())
                .isEqualTo(na.path("data").path("id").asLong());

        mockMvc.perform(get("/api/repair-tasks").param("orderId", String.valueOf(order.getId())))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void second_fail_submission_while_repair_open_is_400_and_creates_no_extra_task() throws Exception {
        WorkOrder order = newOrderWithSteps("WO-API-3");
        String submit = "{\"order\":{\"id\":%d},\"stepName\":\"AOI\",\"defectType\":\"偏移\","
                .formatted(order.getId())
                + "\"defectPoints\":4,\"sampleQty\":40,\"inspector\":\"孙悦\",\"requestId\":\"%s\"}";

        mockMvc.perform(post("/api/inspections").contentType(MediaType.APPLICATION_JSON)
                .content(submit.formatted(UUID.randomUUID().toString()))).andExpect(status().isOk());

        // 在返修未闭环前再次提交不合格 → 400 中文提示，返修任务仍是 1 个
        mockMvc.perform(post("/api/inspections").contentType(MediaType.APPLICATION_JSON)
                .content(submit.formatted(UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未闭环返修")));

        mockMvc.perform(get("/api/repair-tasks").param("orderId", String.valueOf(order.getId())))
                .andExpect(jsonPath("$.data.length()").value(1));
        // 第二次质检也不应留下记录
        mockMvc.perform(get("/api/inspections").param("orderId", String.valueOf(order.getId())))
                .andExpect(jsonPath("$.data.length()").value(1));
    }
}
