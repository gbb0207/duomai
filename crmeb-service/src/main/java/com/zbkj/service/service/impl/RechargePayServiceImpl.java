package com.zbkj.service.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.zbkj.common.constants.Constants;
import com.zbkj.common.constants.WeChatConstants;
import com.zbkj.common.utils.DateUtil;
import com.zbkj.common.model.finance.UserRecharge;
import com.zbkj.common.model.user.User;
import com.zbkj.common.model.user.UserBill;
import com.zbkj.common.utils.RestTemplateUtil;
import com.zbkj.service.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;


/**
 * 支付类
 * +----------------------------------------------------------------------
 * | CRMEB [ CRMEB赋能开发者，助力企业发展 ]
 * +----------------------------------------------------------------------
 * | Copyright (c) 2016~2022 https://www.crmeb.com All rights reserved.
 * +----------------------------------------------------------------------
 * | Licensed CRMEB并不是自由软件，未经许可不能去掉CRMEB相关版权
 * +----------------------------------------------------------------------
 * | Author: CRMEB Team <admin@crmeb.com>
 * +----------------------------------------------------------------------
 */
@Service
public class RechargePayServiceImpl implements RechargePayService {

    @Autowired
    private UserRechargeService userRechargeService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private UserTokenService userTokenService;

    @Autowired
    private UserService userService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private UserBillService userBillService;

    @Autowired
    private RestTemplateUtil restTemplateUtil;

    @Autowired
    private WechatNewService wechatNewService;

    /**
     * 支付成功处理
     * 增加余额，userBill记录
     * @param userRecharge 充值订单
     */
    @Override
    public Boolean paySuccess(UserRecharge userRecharge) {
        userRecharge.setPaid(true);
        userRecharge.setPayTime(DateUtil.nowDateTime());

        User user = userService.getById(userRecharge.getUid());

        BigDecimal payPrice = userRecharge.getPrice().add(userRecharge.getGivePrice());
        BigDecimal balance = user.getNowMoney().add(payPrice);
        // 余额变动对象
        UserBill userBill = new UserBill();
        userBill.setUid(userRecharge.getUid());
        userBill.setLinkId(userRecharge.getOrderId());
        userBill.setPm(1);
        userBill.setTitle("充值支付");
        userBill.setCategory(Constants.USER_BILL_CATEGORY_MONEY);
        userBill.setType(Constants.USER_BILL_TYPE_PAY_RECHARGE);
        userBill.setNumber(payPrice);
        userBill.setBalance(balance);
        // TODO: 微信发货
        JSONObject jsonObject = new JSONObject();
        JSONObject orderKey = new JSONObject();
        JSONObject shipping = new JSONObject();
        JSONObject payer = new JSONObject();
        List<JSONObject> shippingList = new ArrayList<>();

        // 订单单号类型，用于确认需要上传详情的订单。枚举值1，使用下单商户号和商户侧单号；枚举值2，使用微信支付单号。
        orderKey.put("order_number_type", "1");
        orderKey.put("mchid", systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_WE_CHAT_MCH_ID));
        // 商户系统内部订单号，只能是数字、大小写字母`_-*`且在同一个商户号下唯一
        orderKey.put("out_trade_no", userRecharge.getOrderId());

        jsonObject.put("order_key", orderKey);
        // 1、实体物流配送采用快递公司进行实体物流配送形式 2、同城配送 3、虚拟商品，虚拟商品，例如话费充值，点卡等，无实体配送形式 4、用户自提
        jsonObject.put("logistics_type", "3");
        // 发货模式，发货模式枚举值：1、UNIFIED_DELIVERY（统一发货）2、SPLIT_DELIVERY（分拆发货） 示例值: UNIFIED_DELIVERY
        jsonObject.put("delivery_mode", "1");

        shipping.put("item_desc", "余额充值-虚拟发货");
        jsonObject.put("shipping_list", shippingList);
        jsonObject.put("upload_time", DateUtil.nowDateTime("YYYY-MM-DDThh:mm:ss.sss±hh:mm"));

        payer.put("openid", userTokenService.getTokenByUserId(userRecharge.getUid() ,2));
        jsonObject.put("payer", payer);
        System.out.println("6.28 余额虚拟发货:" + jsonObject.toJSONString());
        // 虚拟发货
        String url = StrUtil.format(WeChatConstants.WECHAT_SHIPMENT_API_URL, wechatNewService.getMiniAccessToken());
        String s = restTemplateUtil.postJsonData(url, new JSONObject());
        System.out.println("6.28 余额虚拟发货返回结果:" + s);
        userBill.setMark(StrUtil.format("余额增加了{}元", payPrice));
        userBill.setStatus(1);
        userBill.setCreateTime(DateUtil.nowDateTime());

        Boolean execute = transactionTemplate.execute(e -> {
            // 订单变动
            userRechargeService.updateById(userRecharge);
            // 余额变动
            userService.operationNowMoney(user.getUid(), payPrice, user.getNowMoney(), "add");
            // 创建记录
            userBillService.save(userBill);
            return Boolean.TRUE;
        });
        return execute;
    }
}
