package main

import "github.com/zngp/server/internal/model"

func buildDefaultTemplates() []model.InspectionTemplate {
	return []model.InspectionTemplate{
		{
			Name:        "燃气入户安检",
			Description: "检查燃气入户安检过程是否合规",
			Category:    "gas_safety",
			IsActive:    true,
			Items: []model.InspectionItem{
				{ItemNumber: 1, Name: "亮明身份", Description: "安检员是否自报姓名、工号、单位", Category: "服务礼仪", IsRequired: true, Weight: 1},
				{ItemNumber: 2, Name: "说明来意", Description: "是否说明本次入户安检的目的", Category: "服务礼仪", IsRequired: true, Weight: 1},
				{ItemNumber: 3, Name: "检查燃气管道", Description: "检查管道及连接处是否漏气（用检漏仪或肥皂水）", Category: "安全检查", IsRequired: true, Weight: 3},
				{ItemNumber: 4, Name: "检查燃气表", Description: "检查燃气表运行状态是否正常", Category: "安全检查", IsRequired: true, Weight: 2},
				{ItemNumber: 5, Name: "检查燃气灶具", Description: "检查灶具是否合规、有无熄火保护装置", Category: "安全检查", IsRequired: true, Weight: 2},
				{ItemNumber: 6, Name: "检查燃气热水器", Description: "检查热水器安装是否合规（排烟管道、通风等）", Category: "安全检查", IsRequired: true, Weight: 2},
				{ItemNumber: 7, Name: "检查报警器", Description: "检查燃气泄漏报警器是否工作正常", Category: "安全检查", IsRequired: true, Weight: 2},
				{ItemNumber: 8, Name: "检查通风条件", Description: "检查用气场所通风是否良好", Category: "安全检查", IsRequired: true, Weight: 1},
				{ItemNumber: 9, Name: "检查软管/阀门", Description: "检查连接软管是否老化、阀门是否完好", Category: "安全检查", IsRequired: true, Weight: 2},
				{ItemNumber: 10, Name: "告知安全事项", Description: "是否向用户告知安全用气注意事项", Category: "安全宣传", IsRequired: true, Weight: 1},
				{ItemNumber: 11, Name: "隐患告知", Description: "发现隐患时是否明确告知用户并记录", Category: "安全处理", IsRequired: true, Weight: 2},
				{ItemNumber: 12, Name: "用户签字确认", Description: "是否让用户签字确认安检结果", Category: "流程规范", IsRequired: true, Weight: 1},
			},
		},
		{
			Name:        "客户拜访合规检查",
			Description: "检查客户拜访过程是否合规",
			Category:    "customer_visit",
			IsActive:    true,
			Items: []model.InspectionItem{
				{ItemNumber: 1, Name: "预约确认", Description: "是否提前与客户确认拜访时间", Category: "拜访准备", IsRequired: true, Weight: 1},
				{ItemNumber: 2, Name: "自我介绍", Description: "是否自报姓名、公司、职位", Category: "服务礼仪", IsRequired: true, Weight: 1},
				{ItemNumber: 3, Name: "明确拜访目的", Description: "是否说明本次拜访的目的和议程", Category: "服务礼仪", IsRequired: true, Weight: 1},
				{ItemNumber: 4, Name: "了解客户需求", Description: "是否主动询问和了解客户当前需求", Category: "沟通技巧", IsRequired: true, Weight: 2},
				{ItemNumber: 5, Name: "产品/服务介绍", Description: "是否清晰介绍产品或服务的特点和优势", Category: "销售技巧", IsRequired: false, Weight: 1},
				{ItemNumber: 6, Name: "客户异议处理", Description: "是否认真听取并回应客户的疑问和顾虑", Category: "沟通技巧", IsRequired: true, Weight: 2},
				{ItemNumber: 7, Name: "记录关键信息", Description: "是否记录客户反馈的重要信息", Category: "流程规范", IsRequired: true, Weight: 1},
				{ItemNumber: 8, Name: "明确后续行动", Description: "是否与客户明确下一步行动和时间节点", Category: "流程规范", IsRequired: true, Weight: 2},
				{ItemNumber: 9, Name: "礼貌结束", Description: "是否礼貌结束拜访并表示感谢", Category: "服务礼仪", IsRequired: true, Weight: 1},
				{ItemNumber: 10, Name: "遵守合规要求", Description: "是否遵守行业合规要求（如不得虚假宣传、不得承诺无法兑现的条件）", Category: "合规", IsRequired: true, Weight: 2},
			},
		},
	}
}