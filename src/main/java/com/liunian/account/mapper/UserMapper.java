package com.liunian.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liunian.account.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
