package com.qiyi.service.futu.domain;

/**
 * 板块信息
 */
public class PlateInfo {
    /**
     * 板块
     */
    private Security plate;
    
    /**
     * 板块名字
     */
    private String name;
    
    /**
     * PlateSetType 板块类型, 仅3207（获取股票所属板块）协议返回该字段
     */
    private Integer plateType;

    public Security getPlate() {
        return plate;
    }

    public void setPlate(Security plate) {
        this.plate = plate;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getPlateType() {
        return plateType;
    }

    public void setPlateType(Integer plateType) {
        this.plateType = plateType;
    }
}
