package com.example.edog.controller;

import com.example.edog.configurer.CosProperties;
import com.example.edog.service.CosPostPolicyService;
import com.example.edog.service.TencentStsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CosControllerTest {

    private MockMvc mockMvc;

    private CosProperties cosProperties;

    private TencentStsService tencentStsService;

    @BeforeEach
    void setUp() {
        cosProperties = new CosProperties();
        cosProperties.setBucket("testbucket-1250000000");
        cosProperties.setRegion("ap-guangzhou");
        cosProperties.setSecretId("AKID_TEST");
        cosProperties.setSecretKey("SK_TEST");
        cosProperties.setUseTempCredential(false);
        cosProperties.setRequireLogin(false);
        cosProperties.setLoginUserHeader("X-User-Id");
        cosProperties.setPolicyExpireSeconds(300);
        cosProperties.setMaxFileSizeBytes(2L * 1024 * 1024);
        cosProperties.setUploadHost(null);
        cosProperties.setPublicHost(null);

        tencentStsService = mock(TencentStsService.class);

        CosPostPolicyService cosPostPolicyService = new CosPostPolicyService(
                cosProperties,
                tencentStsService,
                new ObjectMapper()
        );
        CosController controller = new CosController(cosPostPolicyService, cosProperties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldReturnPolicyWhenRequestValid() throws Exception {
        mockMvc.perform(get("/api/cos/post-policy")
                        .param("scene", "avatar")
                        .param("userId", "10001")
                        .param("fileExt", "jpg")
                        .param("contentType", "image/jpeg")
                        .header("X-User-Id", "10001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.url").value("https://testbucket-1250000000.cos.ap-guangzhou.myqcloud.com"))
                .andExpect(jsonPath("$.data.cosKey", containsString("avatars/u_10001/")))
                .andExpect(jsonPath("$.data.qAk").value("AKID_TEST"))
                .andExpect(jsonPath("$.data.qSignature", not(nullValue())));
    }

    @Test
    void shouldReturnBadRequestWhenFileExtInvalid() throws Exception {
        mockMvc.perform(get("/api/cos/post-policy")
                        .param("scene", "avatar")
                        .param("userId", "10001")
                        .param("fileExt", "exe")
                        .param("contentType", "application/octet-stream"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("仅支持 jpg/jpeg/png/webp/gif 图片上传")));
    }

    @Test
    void shouldReturnUnauthorizedWhenRequireLoginAndNoHeader() throws Exception {
        cosProperties.setRequireLogin(true);

        mockMvc.perform(get("/api/cos/post-policy")
                        .param("scene", "avatar")
                        .param("userId", "10001")
                        .param("fileExt", "png")
                        .param("contentType", "image/png"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }
}
