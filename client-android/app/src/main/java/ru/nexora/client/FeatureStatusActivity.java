package ru.nexora.client;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Development dashboard showing the implemented, partial and missing NEXORA
 * client/gameplay milestones. Keep these entries aligned with verified code.
 */
public final class FeatureStatusActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(12, 16, 25);
    private static final int PANEL = Color.rgb(24, 31, 45);
    private static final int TEXT = Color.rgb(240, 244, 252);
    private static final int MUTED = Color.rgb(157, 171, 193);
    private static final int GREEN = Color.rgb(83, 196, 137);
    private static final int YELLOW = Color.rgb(255, 190, 82);
    private static final int RED = Color.rgb(244, 105, 115);
    private LinearLayout page;
    private int completed;
    private int partial;
    private int missing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createScreen());
    }

    private ScrollView createScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(34), dp(22), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView logo = text("NEXORA", 32, TEXT, true);
        logo.setGravity(Gravity.CENTER);
        logo.setLetterSpacing(0.12f);
        page.addView(logo, matchWrap());

        TextView subtitle = text("ПРОГРЕСС РАЗРАБОТКИ", 12, GREEN, true);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLetterSpacing(0.10f);
        space(page, 8);
        page.addView(subtitle, matchWrap());
        space(page, 22);

        LinearLayout summary = card();
        page.addView(summary, matchWrap());
        summary.addView(text("СОСТОЯНИЕ ПРОЕКТА", 12, MUTED, true), matchWrap());
        space(summary, 8);
        summary.addView(text("Это честный список по текущему коду. «Готово» означает, что основа уже написана; это не означает, что функция полностью завершена или прошла все проверки.", 13, TEXT, false), matchWrap());
        space(summary, 12);
        TextView totals = text("Считаем функции ниже…", 14, TEXT, true);
        summary.addView(totals, matchWrap());

        addSection("УЖЕ ДОБАВЛЕНО", GREEN,
                "Экран и основа Android-клиента",
                "Подключение к NEXORA-серверу через NetherNet/WebRTC (режим разработки)",
                "Обмен приветствием NEXORA v1 и получение информации о мире",
                "Просмотр области 3×3 чанка в 3D через OpenGL ES 2.0",
                "Поворот камеры пальцем и приближение двумя пальцами",
                "Выбор встроенного скина и импорт PNG с предпросмотром",
                "Экран инвентаря на 36 ячеек и прототип сетевых действий");

        addSection("ЧАСТИЧНО ГОТОВО", YELLOW,
                "Генерация плоского стартового чанка на стороне ядра",
                "Основа хранения и загрузки мира на стороне ядра",
                "Асинхронная генерация и загрузка чанков",
                "Управление движением и отображение других игроков — пока прототип",
                "Инвентарь и действия с предметами — зависят от сервера",
                "Пакеты Bedrock 1.26.60 — отдельные кодеки, но не полная совместимость");

        addSection("ЕЩЁ НЕ ДОБАВЛЕНО", RED,
                "Одиночный мир, запускаемый прямо внутри Android-клиента",
                "Создание и сохранение одиночных миров на телефоне",
                "Полноценное разрушение и установка блоков",
                "Генерация обычного ландшафта, биомов и деревьев",
                "Полная физика игрока, столкновения и полноценный режим от первого лица",
                "Мобы, крафт и игровые механики выживания",
                "Полная авторизация и безопасный публичный мультиплеер");

        totals.setText("Добавлено: " + completed + "  •  Частично: " + partial
                + "  •  В планах: " + missing);

        space(page, 14);
        LinearLayout note = card();
        page.addView(note, matchWrap());
        note.addView(text("ВАЖНО", 12, YELLOW, true), matchWrap());
        space(note, 6);
        note.addView(text("Сейчас клиент показывает серверный мир-прототип. Одиночный мир в приложении ещё не подключён. Сетевой режим разработки не предназначен для открытого интернета.", 13, MUTED, false), matchWrap());

        space(page, 18);
        Button openClient = button("ОТКРЫТЬ КЛИЕНТ");
        page.addView(openClient, matchWrap());
        openClient.setOnClickListener(view ->
                startActivity(new Intent(this, MainActivity.class)));

        space(page, 10);
        TextView footer = text("СТАТУС ОБНОВЛЯЕТСЯ ВМЕСТЕ С РАЗРАБОТКОЙ", 10, MUTED, true);
        footer.setGravity(Gravity.CENTER);
        page.addView(footer, matchWrap());
        return scroll;
    }

    private void addSection(String title, int color, String... items) {
        space(page, 16);
        LinearLayout section = card();
        page.addView(section, matchWrap());
        section.addView(text(title, 12, color, true), matchWrap());
        space(section, 8);
        for (String item : items) {
            TextView row = text("•  " + item, 14, TEXT, false);
            row.setLineSpacing(dp(3), 1.0f);
            row.setPadding(0, dp(5), 0, dp(5));
            section.addView(row, matchWrap());
            if (color == GREEN) completed++;
            else if (color == YELLOW) partial++;
            else missing++;
        }
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(15), dp(14), dp(15), dp(14));
        layout.setBackground(rounded(PANEL, 16));
        return layout;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(rounded(Color.rgb(55, 112, 205), 12));
        return button;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void space(LinearLayout parent, int height) {
        TextView spacer = new TextView(this);
        parent.addView(spacer, new LinearLayout.LayoutParams(1, dp(height)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
