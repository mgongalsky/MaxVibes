package com.maxvibes.application.port.output

import com.maxvibes.domain.model.approval.ApprovalPolicy

/**
 * Хранилище политики апрувалов.
 *
 * Политика задаётся на проект, поэтому реализация привязана к проекту, а
 * application-слой про способ хранения ничего не знает.
 */
interface ApprovalPolicyPort {

    fun load(): ApprovalPolicy

    fun save(policy: ApprovalPolicy)
}
