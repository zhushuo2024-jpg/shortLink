package com.example.demo.mapper;

import com.example.demo.entity.ShortLink;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ShortLinkMapper {


    @Select("SELECT * FROM short_link WHERE short_code = #{shortCode}")
    ShortLink selectByShortCode(@Param("shortCode") String shortCode);

    @Update("UPDATE short_link SET short_code = #{shortCode} WHERE id = #{id}")
    int updateShortCode(@Param("id") Long id, @Param("shortCode") String shortCode);

    //依据短码查询被点击次数
    @Update("UPDATE short_link SET click_count = #{count} WHERE short_code = #{shortCode}")
    int updateClickCount(@Param("shortCode") String shortCode, @Param("count") Long count);

    @Select("SELECT * FROM short_link")
    List<ShortLink> selectAll();

    @Insert("INSERT INTO short_link(short_code, original_url, channel) VALUES(#{shortCode}, #{originalUrl}, #{channel})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ShortLink shortLink);

    @Update("UPDATE short_link SET click_count = click_count + 1 WHERE short_code = #{shortCode}")
    int incrementClickCount(@Param("shortCode") String shortCode);




}
