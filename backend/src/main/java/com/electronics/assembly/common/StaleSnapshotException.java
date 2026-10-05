package com.electronics.assembly.common;

/**
 * 快照过期异常：确认/提交时发现配置已被再次修改。
 *
 * <p>抛出它时事务【不回滚】（见各写方法上的 noRollbackFor）——
 * 批次已被标为「已失效」必须落库，同时本次确认/提交动作本身失败（400）。
 * 若用普通 BizException，失效标记会随回滚丢失，等于拿旧快照继续放行。</p>
 */
public class StaleSnapshotException extends BizException {

    public StaleSnapshotException(String message) {
        super(message);
    }
}
