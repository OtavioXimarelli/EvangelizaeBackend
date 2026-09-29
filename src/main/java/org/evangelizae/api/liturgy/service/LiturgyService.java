package org.evangelizae.api.liturgy.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.evangelizae.api.liturgy.model.DailyLiturgy;
import org.evangelizae.api.liturgy.model.LiturgicalColor;
import org.evangelizae.api.liturgy.model.LiturgyGroup;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.LiturgicalDay;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.Period;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.Reading;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.ReadingType;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.Source;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.SourceName;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.SourceRole;
import org.evangelizae.api.liturgy.model.LiturgyImportResponse;
import org.evangelizae.api.liturgy.model.LiturgyPrayers;
import org.evangelizae.api.liturgy.model.LiturgyReading;
import org.evangelizae.api.liturgy.model.LiturgySource;
import org.evangelizae.api.liturgy.model.ReadingKind;
import org.evangelizae.api.liturgy.repository.LiturgicalDayDocument;
import org.evangelizae.api.liturgy.repository.LiturgicalDayRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LiturgyService {

    public static final String SUPPORTED_LOCALE = "pt-BR";
    public static final String SUPPORTED_SCHEMA_VERSION = "1.0";

    private static final Pattern HASH_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final LiturgicalDayRepository repository;
    private final Clock clock;

    public LiturgyService(LiturgicalDayRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public DailyLiturgy getToday(String timezone, String locale) {
        var zone = parseZone(timezone);
        validateLocale(locale);
        var date = LocalDate.ofInstant(clock.instant(), zone);

        return repository.findByDate(date)
                .map(this::toDailyLiturgy)
                .orElseThrow(() -> new LiturgyUnavailableException(
                        "A liturgia não está disponível para o dia de " + date));
    }

    public LiturgyImportResponse importBatch(LiturgyImportRequest request) {
        validateImport(request);
        var created = 0;
        var updated = 0;

        for (var day : request.days()) {
            var existing = repository.findByDate(day.date());
            var document = toDocument(request, day, existing);
            repository.save(document);
            if (existing.isPresent()) {
                updated++;
            } else {
                created++;
            }
        }

        var processed = created + updated;
        return new LiturgyImportResponse(
                UUID.randomUUID(),
                LiturgyImportResponse.Status.SUCCESS,
                request.days().size(),
                processed,
                created,
                updated,
                0,
                List.of()
        );
    }

    private void validateImport(LiturgyImportRequest request) {
        if (request.days() == null || request.days().isEmpty()) {
            throw new InvalidLiturgyImportException("At least one liturgy day is required");
        }
        if (!SUPPORTED_SCHEMA_VERSION.equals(request.schemaVersion())) {
            throw new InvalidLiturgyImportException("Unsupported schema version: " + request.schemaVersion());
        }
        validatePeriod(request.period());

        var dates = new HashSet<LocalDate>();
        for (var day : request.days()) {
            if (!dates.add(day.date())) {
                throw new InvalidLiturgyImportException("Duplicate liturgy date: " + day.date());
            }
            if (day.date().isBefore(request.period().from()) || day.date().isAfter(request.period().to())) {
                throw new InvalidLiturgyImportException(
                        "Liturgy date is outside the requested period: " + day.date());
            }
            var primarySource = primarySource(day);
            if (!StringUtils.hasText(primarySource.contentHash())) {
                throw new InvalidLiturgyImportException(
                        "Primary source contentHash is required for " + day.date());
            }
            if (!HASH_PATTERN.matcher(primarySource.contentHash()).matches()) {
                throw new InvalidLiturgyImportException(
                        "Primary source contentHash is invalid for " + day.date());
            }
            normalizeReadings(day);
        }
    }

    private void validatePeriod(Period period) {
        if (period.from().isAfter(period.to())) {
            throw new InvalidLiturgyImportException("Import period start must not be after its end");
        }
    }

    private Source primarySource(LiturgicalDay day) {
        var primarySources = day.sources().stream()
                .filter(source -> source.role() == SourceRole.PRIMARY)
                .toList();
        if (primarySources.size() != 1) {
            throw new InvalidLiturgyImportException(
                    "Exactly one primary source is required for " + day.date());
        }
        var primarySource = primarySources.getFirst();
        if (primarySource.name() != SourceName.CNBB) {
            throw new InvalidLiturgyImportException(
                    "The primary source must be CNBB for " + day.date());
        }
        return primarySource;
    }

    private LiturgicalDayDocument toDocument(
            LiturgyImportRequest request,
            LiturgicalDay day,
            Optional<LiturgicalDayDocument> existing
    ) {
        var primarySource = primarySource(day);
        var now = Instant.now(clock);
        return new LiturgicalDayDocument(
                day.date().toString(),
                day.date(),
                day.celebration().name(),
                day.celebration().liturgicalColor().name(),
                normalizeReadings(day),
                day.celebration().type().name(),
                new LiturgicalDayDocument.Season(
                        day.liturgicalSeason().name(),
                        day.liturgicalSeason().week(),
                        day.liturgicalSeason().liturgicalYear()),
                day.note(),
                day.sources().stream().map(this::toDocument).toList(),
                new LiturgicalDayDocument.Validation(
                        day.validation().status().name(),
                        day.validation().sourcesCompared(),
                        List.copyOf(day.validation().warnings())),
                request.scraperVersion(),
                request.scrapedAt(),
                primarySource.contentHash(),
                primarySource.name().name(),
                primarySource.collectedAt(),
                existing.map(LiturgicalDayDocument::createdAt).orElse(now),
                now,
                now
        );
    }

    private LiturgicalDayDocument.Source toDocument(Source source) {
        return new LiturgicalDayDocument.Source(
                source.name().name(),
                source.role().name(),
                source.url(),
                source.collectedAt(),
                source.sourceHash(),
                source.contentHash()
        );
    }

    private List<LiturgicalDayDocument.Reading> normalizeReadings(LiturgicalDay day) {
        var readings = new ArrayList<LiturgicalDayDocument.Reading>();
        for (var reading : day.parts().readings()) {
            if (reading.options() != null && !reading.options().isEmpty()) {
                if (StringUtils.hasText(reading.text())) {
                    throw new InvalidLiturgyImportException(
                            "A reading cannot contain both text and options for " + day.date());
                }
                for (var option : reading.options()) {
                    readings.add(toReading(ReadingKind.EXTRA, option, reading, day.date()));
                }
                continue;
            }
            readings.add(toReading(publicKind(reading.type()), reading, null, day.date()));
        }
        if (readings.isEmpty()) {
            throw new InvalidLiturgyImportException("No readings available for " + day.date());
        }
        return List.copyOf(readings);
    }

    private LiturgicalDayDocument.Reading toReading(
            ReadingKind kind,
            Reading reading,
            Reading parent,
            LocalDate date
    ) {
        if (!StringUtils.hasText(reading.text())) {
            throw new InvalidLiturgyImportException("Reading text is required for " + date);
        }
        if (reading.options() != null) {
            throw new InvalidLiturgyImportException("Nested reading options are not supported for " + date);
        }
        return new LiturgicalDayDocument.Reading(
                kind.name(),
                firstText(reading.title(), reading.reference(), parent == null ? null : parent.title(),
                        reading.type().name()),
                reading.reference(),
                reading.text(),
                null
        );
    }

    private ReadingKind publicKind(ReadingType type) {
        return switch (type) {
            case ACCLAMATION, SEQUENCE -> ReadingKind.EXTRA;
            default -> ReadingKind.valueOf(type.name());
        };
    }

    private String firstText(String... values) {
        return Arrays.stream(values)
                .filter(StringUtils::hasText)
                .findFirst()
                .map(String::trim)
                .orElseThrow(() -> new InvalidLiturgyImportException("Reading title is required"));
    }

    private DailyLiturgy toDailyLiturgy(LiturgicalDayDocument document) {
        var readings = document.readings();
        if (readings == null || readings.isEmpty()) {
            throw new LiturgyUnavailableException(
                    "A liturgia não possui leituras para o dia de " + document.date());
        }

        var groups = Arrays.stream(ReadingKind.values())
                .map(kind -> toGroup(kind, readings))
                .filter(Objects::nonNull)
                .toList();
        if (groups.isEmpty()) {
            throw new LiturgyUnavailableException(
                    "A liturgia não possui grupos de leitura para o dia de " + document.date());
        }

        var fetchedAt = document.fetchedAt() != null ? document.fetchedAt() : document.updatedAt();
        if (fetchedAt == null) {
            fetchedAt = document.createdAt();
        }
        if (fetchedAt == null) {
            throw new LiturgyUnavailableException(
                    "A liturgia não possui data de atualização para o dia de " + document.date());
        }

        // A document whose source is unknown must not be served. The previous
        // fallback labelled it "mongodb", which the UI would then present to the
        // user as the liturgical source. 503 is the honest answer; reconcile the
        // collection before deploying this.
        if (!StringUtils.hasText(document.provider())) {
            throw new LiturgyUnavailableException(
                    "A liturgia do dia de " + document.date() + " não declara a fonte de origem");
        }

        return new DailyLiturgy(
                document.date(),
                document.title(),
                LiturgicalColor.valueOf(document.color()),
                new LiturgyPrayers(null, null, null),
                groups,
                new LiturgySource(
                        document.provider(),
                        fetchedAt,
                        LiturgySource.Freshness.LIVE)
        );
    }

    private LiturgyGroup toGroup(ReadingKind kind, List<LiturgicalDayDocument.Reading> readings) {
        var items = readings.stream()
                .filter(reading -> kind.name().equals(reading.kind()))
                .map(reading -> new LiturgyReading(
                        reading.title(), reading.reference(), reading.text(), reading.refrain()))
                .toList();
        return items.isEmpty() ? null : new LiturgyGroup(kind, items);
    }

    private ZoneId parseZone(String timeZone) {
        if (!StringUtils.hasText(timeZone)) {
            throw new InvalidLiturgyRequestException("timezone is required");
        }
        try {
            return ZoneId.of(timeZone);
        } catch (ZoneRulesException exception) {
            throw new InvalidLiturgyRequestException("timezone must be a valid IANA timezone", exception);
        }
    }

    private void validateLocale(String locale) {
        if (!SUPPORTED_LOCALE.equals(locale)) {
            throw new InvalidLiturgyRequestException("locale must be pt-BR");
        }
    }
}
