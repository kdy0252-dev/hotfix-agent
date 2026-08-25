document.addEventListener("DOMContentLoaded", () => {
    const locale = window.flatpickr?.l10ns?.ko;

    window.flatpickr?.(".date-time-picker", {
        altFormat: "Y년 m월 d일 H:i",
        altInput: true,
        allowInput: false,
        dateFormat: "Y-m-d\\TH:i",
        enableTime: true,
        locale,
        minuteIncrement: 1,
        monthSelectorType: "static",
        time_24hr: true
    });

    const toastRegion = document.querySelector("#toast-region");
    const chatDrawer = document.querySelector("#chat-drawer");
    const chatLauncher = document.querySelector("#chat-launcher");
    const chatClose = document.querySelector("#chat-close");
    const chatMessages = document.querySelector("#chat-messages");
    const confirmationModal = document.querySelector("#action-confirmation-modal");
    const confirmationMessage = document.querySelector("#action-confirmation-message");
    const confirmationAccept = document.querySelector("[data-confirmation-accept]");
    const confirmationCancelButtons = document.querySelectorAll("[data-confirmation-cancel]");
    const handledErrors = new WeakSet();
    const promotedAnalyses = new Set();
    let pendingConfirmation;
    let selectedWorkflowReference = "";

    const configurePagination = (container) => {
        if (!container || container.dataset.paginationReady === "true") {
            return;
        }
        const items = [...container.querySelectorAll(":scope > [data-page-item]")];
        const sizeSelect = container.querySelector("[data-page-size]");
        const status = container.querySelector("[data-page-status]");
        const previous = container.querySelector("[data-page-previous]");
        const next = container.querySelector("[data-page-next]");
        const signalFilter = container.querySelector("[data-signal-filter]");
        const filterCount = container.querySelector("[data-filter-count]");
        if (!sizeSelect || items.length === 0) {
            return;
        }
        container.dataset.paginationReady = "true";
        let currentPage = 0;
        const renderPage = () => {
            const pageSize = Number(sizeSelect.value);
            const filteredItems = items.filter(item => !signalFilter
                || signalFilter.value === "ALL"
                || item.dataset.signalSeverity === signalFilter.value);
            const totalPages = Math.max(1, Math.ceil(filteredItems.length / pageSize));
            currentPage = Math.min(currentPage, totalPages - 1);
            const pageStart = currentPage * pageSize;
            items.forEach(item => {
                const filteredIndex = filteredItems.indexOf(item);
                const hidden = filteredIndex < pageStart
                    || filteredIndex >= pageStart + pageSize;
                item.hidden = hidden;
                item.classList.toggle("pagination-filtered-out", hidden);
            });
            if (filterCount) {
                filterCount.textContent = `${filteredItems.length} signals`;
            }
            if (status) {
                status.textContent = `${currentPage + 1} / ${totalPages} 페이지`;
            }
            if (previous) {
                previous.disabled = currentPage === 0;
            }
            if (next) {
                next.disabled = currentPage >= totalPages - 1;
            }
        };
        sizeSelect.addEventListener("change", () => {
            currentPage = 0;
            renderPage();
        });
        signalFilter?.addEventListener("change", () => {
            currentPage = 0;
            renderPage();
        });
        previous?.addEventListener("click", () => {
            currentPage = Math.max(0, currentPage - 1);
            renderPage();
        });
        next?.addEventListener("click", () => {
            currentPage += 1;
            renderPage();
        });
        renderPage();
    };

    const configurePaginatedLists = (root) => {
        const lists = [
            ...(root?.matches?.("[data-paginated-list]") ? [root] : []),
            ...(root?.querySelectorAll?.("[data-paginated-list]") || [])
        ];
        lists.forEach(configurePagination);
    };

    const configureWorkflowFilter = (workflowList) => {
        if (!workflowList) {
            return;
        }
        const select = workflowList.querySelector("[data-workflow-filter]");
        const count = workflowList.querySelector("[data-workflow-filter-count]");
        if (!select) {
            return;
        }
        const applyFilter = () => {
            selectedWorkflowReference = select.value;
            let visibleCount = 0;
            workflowList.querySelectorAll(":scope > .workflow-card").forEach((card) => {
                const visible = !selectedWorkflowReference
                    || card.dataset.workflowReference === selectedWorkflowReference;
                card.hidden = !visible;
                card.classList.toggle("workflow-filtered-out", !visible);
                visibleCount += visible ? 1 : 0;
            });
            if (count) {
                count.textContent = `${visibleCount}개 작업`;
            }
        };
        if ([...select.options].some(option => option.value === selectedWorkflowReference)) {
            select.value = selectedWorkflowReference;
        }
        if (workflowList.dataset.workflowFilterReady !== "true") {
            workflowList.dataset.workflowFilterReady = "true";
            select.addEventListener("change", applyFilter);
        }
        applyFilter();
    };

    const setChatOpen = (open) => {
        chatDrawer?.classList.toggle("open", open);
        chatDrawer?.setAttribute("aria-hidden", String(!open));
        chatLauncher?.setAttribute("aria-expanded", String(open));
        if (open) {
            window.setTimeout(() => document.querySelector("#command-text")?.focus(), 120);
        }
    };

    chatLauncher?.addEventListener("click", () => {
        setChatOpen(!chatDrawer?.classList.contains("open"));
    });
    chatClose?.addEventListener("click", () => setChatOpen(false));

    const scrollChatToLatest = () => {
        if (chatMessages) {
            chatMessages.scrollTop = chatMessages.scrollHeight;
        }
    };

    const appendUserMessage = (text) => {
        const history = document.querySelector("#chat-history");
        if (!history || !text) {
            return;
        }
        const message = document.createElement("div");
        message.className = "chat-message user";
        message.textContent = text;
        history.append(message);
        scrollChatToLatest();
    };

    const archiveCommandResult = () => {
        const result = document.querySelector("#command-result");
        const history = document.querySelector("#chat-history");
        if (!result?.hasChildNodes() || !history) {
            return;
        }
        const archived = document.createElement("div");
        archived.className = "chat-transcript-item";
        while (result.firstChild) {
            archived.append(result.firstChild);
        }
        history.append(archived);
    };

    const isAffirmative = (text) => {
        const compact = text.replace(/\s+/g, "");
        return /^(응+|네|예|ㅇㅇ|좋아|진행해|yes|ok)/i.test(compact);
    };

    const showDuplicateConfirmation = (form, normalizedText) => {
        const result = document.querySelector("#command-result");
        if (!result) {
            return;
        }
        const card = document.createElement("div");
        card.className = "result-card duplicate-confirmation";
        const message = document.createElement("p");
        message.textContent = "같은 요청을 이미 해석했습니다. AI로 다시 해석할까요?";
        const confirm = document.createElement("button");
        confirm.type = "button";
        confirm.className = "secondary";
        confirm.textContent = "중복 요청 다시 해석";
        confirm.addEventListener("click", () => {
            form.dataset.duplicateConfirmed = normalizedText;
            form.requestSubmit();
        });
        card.append(message, confirm);
        result.replaceChildren(card);
    };

    const configureCommandForm = (form) => {
        if (!form || form.dataset.commandConfigured === "true") {
            return;
        }
        form.dataset.commandConfigured = "true";
        form.querySelector("textarea[name='text']")?.addEventListener("keydown", (event) => {
            if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
                event.preventDefault();
                form.requestSubmit();
            }
        });
        form.addEventListener("submit", (event) => {
            const textarea = form.querySelector("textarea[name='text']");
            const normalizedText = textarea?.value.trim().replace(/\s+/g, " ") || "";
            const confirmationForms = document.querySelectorAll(
                "#command-result [data-chat-confirm-form]"
            );
            if (isAffirmative(normalizedText) && confirmationForms.length === 1) {
                event.preventDefault();
                event.stopImmediatePropagation();
                appendUserMessage(normalizedText);
                textarea.value = "";
                confirmationForms[0].dataset.userMessageAdded = "true";
                confirmationForms[0].requestSubmit(
                    confirmationForms[0].querySelector("button[type='submit']")
                );
                return;
            }
            if (isAffirmative(normalizedText) && confirmationForms.length > 1) {
                event.preventDefault();
                event.stopImmediatePropagation();
                appendUserMessage(normalizedText);
                textarea.value = "";
                const message = document.createElement("div");
                message.className = "chat-message assistant";
                message.textContent = "수정 가능한 원인이 여러 개입니다. 원하는 원인의 번호 버튼을 선택해 주세요.";
                document.querySelector("#chat-history")?.append(message);
                scrollChatToLatest();
                return;
            }
            const confirmed = form.dataset.duplicateConfirmed === normalizedText;
            if (form.dataset.lastSubmittedText === normalizedText && !confirmed) {
                event.preventDefault();
                event.stopImmediatePropagation();
                showDuplicateConfirmation(form, normalizedText);
                return;
            }
            archiveCommandResult();
            appendUserMessage(normalizedText);
            delete form.dataset.duplicateConfirmed;
            form.dataset.lastSubmittedText = normalizedText;
            const idempotencyKey = form.querySelector("input[name='idempotencyKey']");
            if (idempotencyKey) {
                idempotencyKey.value = window.crypto.randomUUID();
            }
        }, true);
    };

    document.querySelectorAll("[data-command-form]").forEach(configureCommandForm);

    document.body.addEventListener("htmx:confirm", (event) => {
        if (!event.detail.question || !confirmationModal) {
            return;
        }
        event.preventDefault();
        if (confirmationMessage) {
            confirmationMessage.textContent = event.detail.question;
        }
        pendingConfirmation = {
            card: event.detail.elt.closest(".workflow-card, .candidate-workflow"),
            element: event.detail.elt,
            issueRequest: () => event.detail.issueRequest(true),
            requestStarted: false
        };
        confirmationModal.showModal();
    });

    confirmationCancelButtons.forEach((button) => {
        button.addEventListener("click", () => {
            if (pendingConfirmation?.requestStarted) {
                return;
            }
            pendingConfirmation = undefined;
            confirmationModal?.close();
        });
    });

    confirmationAccept?.addEventListener("click", () => {
        if (!pendingConfirmation || pendingConfirmation.requestStarted) {
            return;
        }
        pendingConfirmation.requestStarted = true;
        pendingConfirmation.card?.classList.add("action-pending");
        confirmationModal?.classList.add("action-pending");
        confirmationAccept.disabled = true;
        confirmationAccept.textContent = "삭제 처리 중…";
        confirmationCancelButtons.forEach((button) => {
            button.disabled = true;
        });
        pendingConfirmation.element.addEventListener("htmx:afterRequest", () => {
            pendingConfirmation?.card?.classList.remove("action-pending");
            pendingConfirmation = undefined;
            confirmationModal?.classList.remove("action-pending");
            confirmationAccept.disabled = false;
            confirmationAccept.textContent = "작업 취소 및 삭제";
            confirmationCancelButtons.forEach((button) => {
                button.disabled = false;
            });
        }, {once: true});
        pendingConfirmation.issueRequest();
        confirmationModal?.close();
    });

    confirmationModal?.addEventListener("close", () => {
        if (!pendingConfirmation?.requestStarted) {
            pendingConfirmation = undefined;
        }
    });

    document.body.addEventListener("submit", (event) => {
        const form = event.target.closest("[data-chat-confirm-form]");
        if (!form || form.dataset.userMessageAdded === "true") {
            delete form?.dataset.userMessageAdded;
            return;
        }
        appendUserMessage(event.submitter?.textContent?.trim() || "이 원인으로 진행해줘");
    }, true);

    document.body.addEventListener("click", (event) => {
        const openButton = event.target.closest("[data-modal-open]");
        if (openButton) {
            document.getElementById(openButton.dataset.modalOpen)?.showModal();
            return;
        }
        const closeButton = event.target.closest("[data-modal-close]");
        if (closeButton) {
            closeButton.closest("dialog")?.close();
            return;
        }
        if (event.target instanceof HTMLDialogElement) {
            const bounds = event.target.getBoundingClientRect();
            const inside = event.clientX >= bounds.left && event.clientX <= bounds.right
                && event.clientY >= bounds.top && event.clientY <= bounds.bottom;
            if (!inside) {
                event.target.close();
            }
        }
    });

    const showErrorToast = (title, message) => {
        if (!toastRegion) {
            return;
        }
        const toast = document.createElement("div");
        toast.className = "toast";
        toast.setAttribute("role", "alert");

        const content = document.createElement("div");
        const heading = document.createElement("strong");
        const description = document.createElement("p");
        heading.textContent = title || "요청 실패";
        description.textContent = message || "요청을 처리하지 못했습니다.";
        content.append(heading, description);

        const close = document.createElement("button");
        close.type = "button";
        close.setAttribute("aria-label", "알림 닫기");
        close.textContent = "×";
        close.addEventListener("click", () => toast.remove());
        toast.append(content, close);
        toastRegion.append(toast);
        window.setTimeout(() => toast.remove(), 8000);
    };

    const responseError = (responseText) => {
        const documentFragment = new DOMParser().parseFromString(responseText || "", "text/html");
        return {
            code: documentFragment.querySelector(".error-card strong")?.textContent,
            message: documentFragment.querySelector(".error-card p")?.textContent
        };
    };

    const refreshWorkflowCard = async (analysisId, attempt = 0) => {
        const response = await fetch(`/ui/fragments/workflows/${encodeURIComponent(analysisId)}`, {
            headers: {"HX-Request": "true"}
        });
        if (!response.ok) {
            throw new Error(`워크플로 카드 갱신 실패: HTTP ${response.status}`);
        }
        const responseDocument = new DOMParser().parseFromString(
            await response.text(),
            "text/html"
        );
        const workflowCardId = `workflow-analysis-${analysisId}`;
        const responseCard = responseDocument.querySelector(`[id="${workflowCardId}"]`);
        const workflowList = document.querySelector("#workflow-list");
        if (!responseCard && attempt < 5) {
            await new Promise((resolve) => window.setTimeout(resolve, 500 * (attempt + 1)));
            return refreshWorkflowCard(analysisId, attempt + 1);
        }
        if (!responseCard || !workflowList) {
            throw new Error("완료된 분석의 워크플로 카드를 찾지 못했습니다.");
        }
        const importedCard = document.importNode(responseCard, true);
        document.getElementById(workflowCardId)?.remove();
        const toolbar = workflowList.querySelector(".workflow-filter-toolbar");
        if (toolbar) {
            toolbar.after(importedCard);
        } else {
            workflowList.prepend(importedCard);
        }
        window.htmx?.process(importedCard);
        importedCard.classList.add("workflow-card-updated");
        configureWorkflowFilter(workflowList);
    };

    const promoteCompletedAnalyses = (root) => {
        if (!root) {
            return;
        }
        const completedActions = [
            ...(root.matches?.("[data-completed-analysis-id]") ? [root] : []),
            ...(root.querySelectorAll?.("[data-completed-analysis-id]") || [])
        ];
        completedActions.forEach((action) => {
            const analysisId = action.dataset.completedAnalysisId;
            if (!analysisId || promotedAnalyses.has(analysisId)) {
                return;
            }
            promotedAnalyses.add(analysisId);
            refreshWorkflowCard(analysisId).catch((error) => {
                promotedAnalyses.delete(analysisId);
                showErrorToast("WORKFLOW_CARD_REFRESH_FAILED", error.message);
            });
        });
    };

    const synchronizeObservabilityAnalysis = (root) => {
        if (!root) {
            return;
        }
        const actions = [
            ...(root.matches?.(".observability-analysis-action") ? [root] : []),
            ...(root.querySelectorAll?.(".observability-analysis-action") || [])
        ];
        actions.forEach((action) => {
            const analysisKey = action.id.replace("observability-analysis-", "");
            const form = document.querySelector(
                `.observability-analysis-form[data-analysis-key="${CSS.escape(analysisKey)}"]`
            );
            const button = form?.querySelector(".analysis-request-button");
            const running = Boolean(action.querySelector(".trace-analysis-state.running"));
            if (button) {
                button.disabled = running;
                button.classList.toggle("analysis-running", running);
            }
        });
    };

    const refreshWorkflowList = async () => {
        const response = await fetch("/ui/fragments/workflows", {
            headers: {"HX-Request": "true"}
        });
        if (!response.ok) {
            throw new Error(`워크플로 목록 갱신 실패: HTTP ${response.status}`);
        }
        const responseDocument = new DOMParser().parseFromString(
            await response.text(),
            "text/html"
        );
        const responseList = responseDocument.querySelector("#workflow-list");
        const currentList = document.querySelector("#workflow-list");
        if (!responseList || !currentList) {
            throw new Error("워크플로 목록을 찾지 못했습니다.");
        }
        const importedList = document.importNode(responseList, true);
        currentList.replaceWith(importedList);
        window.htmx?.process(importedList);
        configureWorkflowFilter(importedList);
    };

    configurePaginatedLists(document);
    configureWorkflowFilter(document.querySelector("#workflow-list"));
    promoteCompletedAnalyses(document);
    synchronizeObservabilityAnalysis(document);

    document.body.addEventListener("htmx:afterSwap", (event) => {
        const swapped = event.detail.target;
        configurePaginatedLists(swapped);
        configureWorkflowFilter(swapped?.closest?.("#workflow-list")
            || swapped?.querySelector?.("#workflow-list"));
        configureCommandForm(swapped?.matches?.("[data-command-form]")
            ? swapped : swapped?.querySelector?.("[data-command-form]"));
        const currentTarget = swapped?.id ? document.getElementById(swapped.id) : null;
        promoteCompletedAnalyses(swapped);
        promoteCompletedAnalyses(currentTarget);
        synchronizeObservabilityAnalysis(swapped);
        synchronizeObservabilityAnalysis(currentTarget);
        const refreshRequests = [
            ...(swapped?.matches?.("[data-refresh-workflow]") ? [swapped] : []),
            ...(swapped?.querySelectorAll?.("[data-refresh-workflow]") || [])
        ];
        if (refreshRequests.length > 0) {
            refreshWorkflowList().catch((error) => {
                showErrorToast("WORKFLOW_LIST_REFRESH_FAILED", error.message);
            });
        }
        scrollChatToLatest();
    });

    document.body.addEventListener("htmx:afterSettle", (event) => {
        promoteCompletedAnalyses(event.detail.target);
    });

    document.body.addEventListener("click", (event) => {
        const close = event.target.closest("[data-dismiss-analysis-action]");
        close?.closest(".observability-analysis-action")?.replaceChildren();
    });

    document.body.addEventListener("htmx:beforeRequest", (event) => {
        const form = event.detail.elt?.closest?.("[data-command-form]");
        if (!form) {
            return;
        }
        form.classList.add("interpreting");
        form.querySelector("textarea[name='text']")?.setAttribute("disabled", "disabled");
        form.querySelector("button[type='submit']")?.setAttribute("disabled", "disabled");
    });

    document.body.addEventListener("htmx:afterRequest", (event) => {
        const form = event.detail.elt?.closest?.("[data-command-form]");
        if (!form) {
            return;
        }
        form.classList.remove("interpreting");
        form.querySelector("textarea[name='text']")?.removeAttribute("disabled");
        form.querySelector("button[type='submit']")?.removeAttribute("disabled");
        if (event.detail.successful) {
            const textarea = form.querySelector("textarea[name='text']");
            if (textarea) {
                textarea.value = "";
            }
        }
        if (!event.detail.successful) {
            delete form.dataset.lastSubmittedText;
        }
    });

    document.body.addEventListener("htmx:beforeSwap", (event) => {
        const xhr = event.detail.xhr;
        if (xhr.status < 400) {
            return;
        }
        const error = responseError(xhr.responseText);
        showErrorToast(error.code, error.message);
        handledErrors.add(xhr);
        event.detail.shouldSwap = true;
        event.detail.isError = false;
    });

    document.body.addEventListener("htmx:responseError", (event) => {
        const xhr = event.detail.xhr;
        if (!handledErrors.has(xhr)) {
            const error = responseError(xhr.responseText);
            showErrorToast(error.code, error.message);
        }
    });

    document.body.addEventListener("htmx:sendError", () => {
        showErrorToast("NETWORK_ERROR", "서버에 연결하지 못했습니다.");
    });

    document.body.addEventListener("htmx:timeout", () => {
        showErrorToast("REQUEST_TIMEOUT", "요청 시간이 초과되었습니다.");
    });
});
