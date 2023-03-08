    public String creditReview(CreditReviewPO creditReviewPO,String orgNo,String userNo){
        ResultPo resultPo = new ResultPo();
        JSONObject data = new JSONObject();
        //身份证件号
        String certId = creditReviewPO.getCertId();
        //客户姓名
        String cusName = creditReviewPO.getCusName();
        //产品类型
        String productType = creditReviewPO.getProductType();
        //1.在5分钟之内报文相同,返回异常
        Boolean ifOnTime = this.ifOnTime(creditReviewPO);
        if(ifOnTime && creditReviewPO.getProjApplyId()==null){
            resultPo.setCode(ResultCode.SYS_EXCEPTION);
            resultPo.setMessage("5分钟内不可重复进行征信审查！");
            resultPo.setData(data);
            delMonitorKey(certId);
            return commonServiceUtil.getJsonFormat(resultPo);
        }

        //2.落征信授权记录表,前端展示授权书
        AuthCheckPO authCheckPO = this.updateAuthCheck(certId, cusName, creditReviewPO.getBearRole());
        //征信审查记录表，生成征信审查记录
        AuthCheckRecordPO authCheckRecordPO = new AuthCheckRecordPO();
        //主键生成
        authCheckRecordPO.setCreditReviewId(commonServiceUtil.generatorId());
        //合作机构编号
        authCheckRecordPO.setCoopOrgNo(orgNo);
        //银行账户
        authCheckRecordPO.setAccountNo(creditReviewPO.getCardNo());
        //客户姓名
        authCheckRecordPO.setCustName(creditReviewPO.getCusName());
        //身份证号码
        authCheckRecordPO.setCertId(creditReviewPO.getCertId());
        //手机号码
        authCheckRecordPO.setPhone(creditReviewPO.getMobilePhone());
        //合作方案
        authCheckRecordPO.setCooperationPlanNo(creditReviewPO.getCooperatioProgram());
        //业务员编号
        authCheckRecordPO.setBusiManagerId(userNo);
        //风控策略标识
        authCheckRecordPO.setRiskPolicyFlag(productType);
        //查询阶段(一审/二审)
        authCheckRecordPO.setQueryStage(creditReviewPO.getQueryStage());
        //项目申请id
        authCheckRecordPO.setProjectApplyId(creditReviewPO.getProjApplyId());
        //产品配置编号
        authCheckRecordPO.setPolcApplyId(creditReviewPO.getPolcApplyId());
        //待提交
        authCheckRecordPO.setReviewStatus(LeaseConst.WORKFLOW_STATUS_PRESUBMIT);
        //承租人
        authCheckRecordPO.setBearRole(creditReviewPO.getBearRole());
        //客户分类：自然人
        authCheckRecordPO.setCustomerClassify(LeaseConst.CUSTOMER_TYPE_NATURALPERSON);
        //签署方式
        authCheckRecordPO.setSignMethod(CreditReviewConst.ELECTRONIC_SIGN);
        //资管
        String businessModel;
        if ("ZG".equals(creditReviewPO.getBusinessModel())) {
            businessModel = "1";
        } else if ("CS".equals(creditReviewPO.getBusinessModel())) {
            businessModel = "2";
        } else if ("JX".equals(creditReviewPO.getBusinessModel())) {
            businessModel = "3";
        } else if ("ZY".equals(creditReviewPO.getBusinessModel())) {
            businessModel = "4";
        } else {
            businessModel = creditReviewPO.getBusinessModel();
        }
        authCheckRecordPO.setBusinessModel(businessModel);
        //创建方式
        authCheckRecordPO.setCreatedMethod(DealerConst.CREATED_METHOD_INTERFACE);
        //申请时间
        authCheckRecordPO.setAuthCheckApplyDate(DateKit.now());
        authCheckRecordPO.setCreditQueryDate(DateKit.now());
        //创建人
        authCheckRecordPO.setCreatedBy(userNo);
        //添加业务部门
        String orgIdByUserId = creditReviewService.findOrgIdByUserId(userNo);
        authCheckRecordPO.setBelongOrg(orgIdByUserId);
        int save = dataAccessor.save(authCheckRecordPO);
        creditReviewService.copyIdCardOrBusinessCard(cusName,certId,creditReviewPO.getBearRole(),
                authCheckPO.getCheckId(),authCheckRecordPO.getCreditReviewId(),userNo);
        creditReviewService.copyCustAuth(cusName,certId,creditReviewPO.getBearRole(),
                authCheckPO.getCheckId(),authCheckRecordPO.getCreditReviewId(),userNo);
        if(save<1){
            throw new BizException("数据保存失败");
        }
        //3.进件状态
        AuthCheckRecordPO authCheckRecordPOAfter =
           creditReviewService.validateIsCreditReviewAllow(authCheckRecordPO);
        //如果进件状态是退件
        String firstInstanceApproveResult = authCheckRecordPOAfter.getFirstInstanceApproveResult();
        boolean ifRefund = (CreditReviewConst.FIRST_APPROVE_RESULT_REJECT.equals(firstInstanceApproveResult)||
                CreditReviewConst.FIRST_APPROVE_RESULT_RETURN.equals(firstInstanceApproveResult))
                ||(CreditReviewConst.FIRST_APPROVE_RESULT_PASS.equals(firstInstanceApproveResult)&&
                Objects.nonNull(authCheckRecordPOAfter.getReturnReason()));
        if(ifRefund){
            //征信审查有效期内
            delMonitorKey(certId);
            resultPo.setCode(ResultCode.OK);
            //拒绝进件原因
            resultPo.setMessage(authCheckRecordPOAfter.getReturnReason());
            data.put("creditNo", authCheckRecordPOAfter.getCreditReviewId());
            data.put("phaseStatus", authCheckRecordPOAfter.getFirstInstanceApproveResult());
            resultPo.setData(data);
            //回调二审中的征信审查
            if (Objects.nonNull(creditReviewPO.getProjApplyId())) {
                businessApplyService.BusinessApproveFirstCheck(authCheckRecordPOAfter);
            }
        }else {
            //根据条件更新起期和止期
            creditReviewService.updateAuthCheckDate(authCheckPO.getCustName(),
                    authCheckPO.getCertId(),authCheckPO.getBearRole());
            //允许进件的话，调用调百融风控和一审，返回流水号
            authCheckRecordPO.setReviewStatus(LeaseConst.WORKFLOW_STATUS_APPROVING);
            dataAccessor.save(authCheckRecordPO);
            String cerditNo = authCheckRecordPO.getCreditReviewId();
            String url =  signatureConfig.getInnHostName()+signatureConfig.getFirstReview();
            JSONObject sendJsonObject = this.getSendJsonObject(creditReviewPO, orgNo,cerditNo);
            logger.debug("将要调用风控征信审查发送数据："+sendJsonObject);
            //新增征信查询结果表，补充征信查询时间
            AuthQueryResultPO authQueryResultPO = new AuthQueryResultPO();
            authQueryResultPO.setCreditReviewId(authCheckRecordPO.getCreditReviewId());
            authQueryResultPO.setCustName(cusName);
            authQueryResultPO.setCertId(certId);
            authQueryResultPO.setBearRole(creditReviewPO.getBearRole());
            authQueryResultPO.setCustomerClassify(LeaseConst.CUSTOMER_TYPE_NATURALPERSON);
            authQueryResultPO.setRiskPolicyFlag(productType);
            authQueryResultPO.setQueryStage(creditReviewPO.getQueryStage());
            authQueryResultPO.setIfSuccessGet(YesOrNo.N.toString());
            logger.info("保存数据征信审查结果数据" + authQueryResultPO);
            dataAccessor.save(authQueryResultPO);
            //解析接口平台返回的数据
            try {
                logger.info("发送风控请求参数：" + sendJsonObject);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                    @Override
                    public void afterCommit() {
                        HttpUtils.sendRequest(url, CreditReviewChannelConst.POST_METHOD, sendJsonObject.toJSONString());
                    }
                });
            } catch (BizException e) {
                throw new BizException("发送风控失败");
            }
            data.put("creditNo",authCheckRecordPO.getCreditReviewId());
            resultPo.setCode(ResultCode.OK);
            resultPo.setMessage(MessageConst.SUCCESS);
            resultPo.setData(data);
        }
        return commonServiceUtil.getJsonFormat(resultPo);
    }


