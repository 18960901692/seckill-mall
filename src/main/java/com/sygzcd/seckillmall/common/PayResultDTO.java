package com.sygzcd.seckillmall.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 支付结果 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayResultDTO {

    private String orderNo;

    private Integer status;

    private LocalDateTime payTime;
}