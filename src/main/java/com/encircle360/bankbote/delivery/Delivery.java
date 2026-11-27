package com.encircle360.bankbote.delivery;

import com.encircle360.bankbote.account.Iban;
import com.encircle360.bankbote.erpnext.CompanyBankAccount;
import com.encircle360.bankbote.erpnext.ErpNextClient;
import com.encircle360.bankbote.erpnext.ErpNextException;
import com.encircle360.bankbote.erpnext.ExistingTransaction;
import com.encircle360.bankbote.erpnext.InsertedTransaction;
import com.encircle360.bankbote.source.SourceAccount;
import com.encircle360.bankbote.source.SourceEntries;
import com.encircle360.bankbote.source.SourceException;
import com.encircle360.bankbote.source.SourceTransaction;
import com.encircle360.bankbote.source.TransactionSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One run: reads the accounts of the source, delivers the booked transactions of each mapped
 * account to its one target and leaves every other account alone.
 *
 * <p>Targets run side by side, each in its own virtual thread with its own client, so a target that
 * is down, slow or refuses its key delays and fails only its own accounts. Within a target the
 * accounts run one after another.
 */
public final class Delivery {

  private static final Logger LOG = LoggerFactory.getLogger(Delivery.class);

  /** Oldest first, and by id within a day, as the ids of Hibiscus grow with each new booking. */
  private static final Comparator<SourceTransaction> BOOKING_ORDER =
      Comparator.comparing(SourceTransaction::bookingDate)
          .thenComparing(transaction -> transaction.id().length())
          .thenComparing(SourceTransaction::id);

  private final TransactionSource source;
  private final String sourceKey;
  private final List<Route> routes;
  private final List<ErpNextClient> targets;
  private final Clock clock;

  public Delivery(
      TransactionSource source,
      String sourceKey,
      List<Route> routes,
      List<ErpNextClient> targets,
      Clock clock) {
    this.source = source;
    this.sourceKey = sourceKey;
    this.routes = List.copyOf(routes);
    this.targets = List.copyOf(targets);
    this.clock = clock;
  }

  /** Delivers what is missing of the transactions booked on {@code from} or later. */
  public RunReport run(LocalDate from) {
    Instant startedAt = clock.instant();
    List<SourceAccount> accounts;
    try {
      accounts = source.accounts();
    } catch (SourceException e) {
      return new RunReport(startedAt, clock.instant(), from, e.getMessage(), List.of(), List.of());
    }
    Map<Iban, List<SourceAccount>> byIban = new LinkedHashMap<>();
    List<SourceAccount> withoutIban = new ArrayList<>();
    for (SourceAccount account : accounts) {
      account
          .validIban()
          .ifPresentOrElse(
              iban -> byIban.computeIfAbsent(iban, key -> new ArrayList<>()).add(account),
              () -> withoutIban.add(account));
    }
    List<TargetReport> reports = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<TargetReport>> futures = new ArrayList<>();
      for (ErpNextClient target : targets) {
        List<Route> own =
            routes.stream().filter(route -> route.target().equals(target.target())).toList();
        futures.add(executor.submit(() -> new TargetRun(target, own, byIban, from).run()));
      }
      for (Future<TargetReport> future : futures) {
        reports.add(future.get());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted during a run", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("a target run failed unexpectedly", e.getCause());
    }
    return new RunReport(
        startedAt, clock.instant(), from, null, reports, unmapped(byIban, withoutIban, from));
  }

  private List<UnmappedAccount> unmapped(
      Map<Iban, List<SourceAccount>> byIban, List<SourceAccount> withoutIban, LocalDate from) {
    List<UnmappedAccount> unmapped = new ArrayList<>();
    byIban.forEach((iban, accounts) -> {
      if (routes.stream().noneMatch(route -> route.iban().equals(iban))) {
        accounts.forEach(account ->
            unmapped.add(new UnmappedAccount(iban.masked(), account.id(), found(account, from))));
      }
    });
    withoutIban.forEach(account -> unmapped.add(new UnmappedAccount("none", account.id(), 0)));
    return unmapped;
  }

  private int found(SourceAccount account, LocalDate from) {
    try {
      SourceEntries entries = source.transactions(account, from);
      return entries.transactions().size() + entries.unreadable().size();
    } catch (SourceException e) {
      return -1;
    }
  }

  /** The work of one target in one run, on its own thread. */
  private final class TargetRun {

    private final ErpNextClient target;
    private final List<Route> routes;
    private final Map<Iban, List<SourceAccount>> sourceAccounts;
    private final LocalDate from;
    private List<CompanyBankAccount> companyAccounts;
    private String stopped;

    TargetRun(
        ErpNextClient target,
        List<Route> routes,
        Map<Iban, List<SourceAccount>> sourceAccounts,
        LocalDate from) {
      this.target = target;
      this.routes = routes;
      this.sourceAccounts = sourceAccounts;
      this.from = from;
    }

    TargetReport run() {
      List<AccountReport> accounts = new ArrayList<>();
      for (Route route : routes) {
        accounts.add(account(route.iban()));
      }
      return new TargetReport(target.target(), accounts, stopped);
    }

    private AccountReport account(Iban iban) {
      Tally tally = new Tally(iban);
      List<SourceAccount> matches = sourceAccounts.getOrDefault(iban, List.of());
      if (matches.size() != 1) {
        return tally.error(
            matches.isEmpty()
                ? "the source has no account with this IBAN"
                : "the source has " + matches.size() + " accounts with this IBAN");
      }
      SourceAccount account = matches.getFirst();
      SourceEntries entries;
      try {
        entries = source.transactions(account, from);
      } catch (SourceException e) {
        return tally.error(e.getMessage());
      }
      tally.found = entries.transactions().size() + entries.unreadable().size();
      tally.failed = entries.unreadable().size();
      entries
          .unreadable()
          .forEach(entry ->
              LOG.warn(
                  "transaction {} of account {} cannot be read: {}",
                  entry.id(),
                  iban,
                  entry.reason()));
      if (!entries.unreadable().isEmpty()) {
        tally.error = entries.unreadable().size() + " transaction(s) cannot be read at the source";
      }
      List<SourceTransaction> booked =
          entries.transactions().stream()
              .filter(transaction -> !transaction.pending())
              .sorted(BOOKING_ORDER)
              .toList();
      tally.pending = entries.transactions().size() - booked.size();
      if (!booked.isEmpty()) {
        deliver(account, booked, tally);
      }
      return tally.report();
    }

    private void deliver(SourceAccount account, List<SourceTransaction> booked, Tally tally) {
      int done = 0;
      try {
        if (stopped != null) {
          throw new Stop(stopped);
        }
        String bankAccount = bankAccount(tally.iban);
        Map<String, List<ExistingTransaction>> existing =
            target
                .existing(
                    bankAccount,
                    booked.stream()
                        .map(t -> BankTransactionMapper.transactionId(sourceKey, t.id()))
                        .toList())
                .stream()
                .collect(Collectors.groupingBy(ExistingTransaction::transactionId));
        for (SourceTransaction transaction : booked) {
          try {
            deliver(transaction, bankAccount, account.currency(), existing, tally);
          } catch (ErpNextException e) {
            if (e.stopsTheTarget() || e.kind() == ErpNextException.Kind.UNAVAILABLE) {
              throw e;
            }
            LOG.warn(
                "transaction {} of account {} was not delivered: {}",
                transaction.id(),
                tally.iban,
                e.getMessage());
            tally.failed++;
            tally.error = e.getMessage();
          }
          done++;
        }
      } catch (ErpNextException e) {
        stopped = e.getMessage();
        tally.failed += booked.size() - done;
        tally.error = e.getMessage();
        LOG.warn("deliveries to target {} stop for this run: {}", target.target(), e.getMessage());
      } catch (Stop e) {
        tally.failed += booked.size() - done;
        tally.error = e.getMessage();
      }
    }

    private void deliver(
        SourceTransaction transaction,
        String bankAccount,
        String currency,
        Map<String, List<ExistingTransaction>> existing,
        Tally tally)
        throws ErpNextException {
      String id = BankTransactionMapper.transactionId(sourceKey, transaction.id());
      List<ExistingTransaction> held = existing.getOrDefault(id, List.of());
      if (held.stream().anyMatch(doc -> doc.docstatus() != 0)) {
        if (held.size() > 1) {
          LOG.warn(
              "transaction id {} is held {} times by target {}", id, held.size(), target.target());
        }
        tally.existing++;
        return;
      }
      if (!held.isEmpty()) {
        throw draft("holds", id);
      }
      InsertedTransaction inserted =
          target.insertSubmitted(
              BankTransactionMapper.map(transaction, sourceKey, bankAccount, currency));
      if (inserted.docstatus() == 0) {
        throw draft("kept", id);
      }
      if (inserted.docstatus() != 1) {
        throw new ErpNextException(
            ErpNextException.Kind.REJECTED,
            "ERPNext "
                + target.target()
                + " answered docstatus "
                + inserted.docstatus()
                + " for a new bank transaction");
      }
      tally.delivered++;
    }

    /**
     * Submitting a draft needs the right to write bank transactions, which the service user does
     * not have; a draft is therefore left to a person and reported, and never inserted again.
     */
    private ErpNextException draft(String verb, String id) {
      return new ErpNextException(
          ErpNextException.Kind.REJECTED,
          "ERPNext "
              + target.target()
              + " "
              + verb
              + " "
              + id
              + " as a draft; submit or delete it there");
    }

    private String bankAccount(Iban iban) throws ErpNextException, Stop {
      if (companyAccounts == null) {
        companyAccounts = target.companyBankAccounts();
      }
      List<String> names =
          companyAccounts.stream()
              .filter(account -> Iban.tryParse(account.iban()).equals(Optional.of(iban)))
              .map(CompanyBankAccount::name)
              .toList();
      if (names.size() != 1) {
        throw new Stop(
            "ERPNext "
                + target.target()
                + (names.isEmpty()
                    ? " has no company bank account with this IBAN"
                    : " has " + names.size() + " company bank accounts with this IBAN"));
      }
      return names.getFirst();
    }
  }

  /** Ends the delivery of one account without stopping the target. */
  private static final class Stop extends Exception {
    Stop(String message) {
      super(message, null, false, false);
    }
  }

  private static final class Tally {
    private final Iban iban;
    private int found;
    private int delivered;
    private int existing;
    private int pending;
    private int failed;
    private String error;

    Tally(Iban iban) {
      this.iban = iban;
    }

    AccountReport error(String message) {
      this.error = message;
      return report();
    }

    AccountReport report() {
      return new AccountReport(iban.masked(), found, delivered, existing, pending, failed, error);
    }
  }
}
