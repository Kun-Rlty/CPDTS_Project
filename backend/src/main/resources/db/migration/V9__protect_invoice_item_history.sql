-- Draft item edits serialize with invoice issue/void using the same parent lock.
-- An item reassignment locks both parents in ascending id order.
CREATE FUNCTION fn_guard_invoice_item_history() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    old_id bigint;
    new_id bigint;
    old_company bigint;
    new_company bigint;
    parent record;
    expected integer;
    seen integer := 0;
BEGIN
    IF TG_OP <> 'INSERT' THEN old_id := OLD.invoice_id; old_company := OLD.company_id; END IF;
    IF TG_OP <> 'DELETE' THEN new_id := NEW.invoice_id; new_company := NEW.company_id; END IF;
    expected := CASE WHEN old_id IS NULL OR new_id IS NULL OR (old_id = new_id AND old_company = new_company) THEN 1 ELSE 2 END;
    FOR parent IN
        SELECT id, status FROM public.invoices
        WHERE (id = old_id AND company_id = old_company) OR (id = new_id AND company_id = new_company)
        ORDER BY id FOR UPDATE
    LOOP
        seen := seen + 1;
        IF parent.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'invoice items can only be changed on draft invoices' USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    IF seen <> expected THEN
        RAISE EXCEPTION 'invoice not found in company' USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_invoice_items_draft_only
BEFORE INSERT OR UPDATE OR DELETE ON invoice_items
FOR EACH ROW EXECUTE FUNCTION fn_guard_invoice_item_history();

-- Without this guard, a database user with the required invoice status update
-- privilege could revert an issued/voided parent to DRAFT and edit its items.
CREATE FUNCTION fn_guard_invoice_lifecycle() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.status = 'ISSUED' AND NEW.status NOT IN ('ISSUED', 'VOIDED'))
       OR (OLD.status = 'VOIDED' AND NEW.status <> 'VOIDED') THEN
        RAISE EXCEPTION 'invoice lifecycle cannot return to draft' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_invoice_lifecycle_forward_only
BEFORE UPDATE OF status ON invoices
FOR EACH ROW EXECUTE FUNCTION fn_guard_invoice_lifecycle();
