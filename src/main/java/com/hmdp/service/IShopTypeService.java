package com.hmdp.service;

import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.result.Result;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryTypeList();
}
