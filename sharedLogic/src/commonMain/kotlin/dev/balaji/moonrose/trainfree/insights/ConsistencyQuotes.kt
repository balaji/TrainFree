package dev.balaji.moonrose.trainfree.insights

import kotlin.random.Random

/**
 * Curated quotes on consistency, discipline, and showing up — one per line so
 * additions or edits are a plain text diff. Served directly without an LLM.
 */
object ConsistencyQuotes {
    private val QUOTES_TEXT = """
"Success isn't always about greatness. It's about consistency. Consistent hard work leads to success. Greatness will come." — Dwayne Johnson
"It's not what we do once in a while that shapes our lives. It's what we do consistently." — Tony Robbins
"What you do every day matters more than what you do once in a while." — Gretchen Rubin
"Discipline is the bridge between goals and accomplishments." — Jim Rohn
"Eighty percent of success is showing up." — Woody Allen
"We are what we repeatedly do. Excellence, then, is not an act, but a habit." — Will Durant
"It does not matter how slowly you go as long as you do not stop." — Confucius
"A river cuts through rock, not because of its power, but because of its persistence." — Jim Watkins
"Skill is only developed by hours and hours of work." — Usain Bolt
"There's no way around hard work. Embrace it. You have to put in the hours because there is always something you can improve on." — Roger Federer
"Champions keep playing until they get it right." — Billie Jean King
"Hard work beats talent when talent doesn't work hard." — Tim Notke
"Continuous effort—not strength or intelligence—is the key to unlocking our potential." — Liane Cardes
"It's hard to beat a person who never gives up." — Babe Ruth
"Nothing will work unless you do." — John Wooden
"Consistency is the DNA of mastery." — Robin Sharma
"Small disciplines repeated with consistency every day lead to great achievements gained slowly over time." — John C. Maxwell
"Great things are not done by impulse, but by a series of small things brought together." — Vincent Van Gogh
"Success is nothing more than a few simple disciplines, practiced every single day." — Jim Rohn
"Perseverance is not a long race; it is many short races one after the other." — Walter Elliot
"Don't watch the clock; do what it does. Keep going." — Sam Levenson
"Everything is hard before it is easy." — Johann Wolfgang von Goethe
"First tell yourself what kind of person you want to be, then do what you have to do." — Epictetus
"Do not give up in disgust or impatience if you do not find action on the right principles consolidated into a habit in all that you do." — Marcus Aurelius
"Amateurs sit and wait for inspiration, the rest of us just get up and go to work." — Stephen King
"Success isn't overnight. It's when every day you get a little bit better than the day before. It all adds up." — Dwayne Johnson
"The harder you work, the harder it is to surrender." — Vince Lombardi
"It's not whether you get knocked down; it's whether you get up." — Vince Lombardi
"Success is no accident. It is hard work, perseverance, learning, studying, sacrifice and most of all, love of what you are doing." — Pelé
"The key is not the will to win. Everybody has that. It is the will to prepare to win that is important." — Bobby Knight
"The will to win means nothing without the will to prepare." — Juma Ikangaa
"Persistence can change failure into an extraordinary achievement." — Matt Biondi
"Patience, persistence and perspiration make an unbeatable combination for success." — Napoleon Hill
"There are no shortcuts to any place worth going." — Beverly Sills
"Look at a stone cutter hammering away at his rock, perhaps a hundred times without as much as a crack showing in it. Yet at the hundred and first blow it will split in two." — Jacob Riis
"Mastery isn't a function of genius or talent. It translates to time, logged on task." — Twyla Tharp
"The secret of getting ahead is getting started." — Mark Twain
"Gold medals aren't really made of gold. They're made of sweat, determination, and a hard-to-find alloy called guts." — Dan Gable
"Don't get bitter, get better." — Tiger Woods
"Don't dream of winning — train for it." — Mo Farah
"Pressure is a privilege." — Billie Jean King
"Training gives us an outlet for suppressed energies created by stress and thus tones the spirit just as exercise conditions the body." — Arnold Schwarzenegger
"Strength is the product of struggle; you must do what others don't to achieve what others won't." — Henry Rollins
"The last three or four reps is what makes the muscle grow. This area of pain divides a champion from someone who is not a champion." — Arnold Schwarzenegger
"I hated every minute of training, but I said, 'Don't quit. Suffer now and live the rest of your life as a champion.'" — Muhammad Ali
"He who is not courageous enough to take risks will accomplish nothing in life." — Muhammad Ali
"The fight is won or lost far away from witnesses—behind the lines, in the gym, and out there on the road." — Muhammad Ali
"I fear not the man who has practiced 10,000 kicks once, but I fear the man who has practiced one kick 10,000 times." — Bruce Lee
"Long-term consistency trumps short-term intensity." — Bruce Lee
"A goal is not always meant to be reached; it often serves simply as something to aim at." — Bruce Lee
"Do not pray for an easy life, pray for the strength to endure a difficult one." — Bruce Lee
"Take care of your body. It's the only place you have to live." — Jim Rohn
"Motivation is what gets you started. Habit is what keeps you going." — Jim Ryun
"Fall seven times, stand up eight." — Japanese Proverb
"A journey of a thousand miles begins with a single step." — Lao Tzu
"Little by little, a little becomes a lot." — Tanzanian Proverb
"Drops of water hollow out a stone, not by force, but by falling often." — Ovid
"How we spend our days is, of course, how we spend our lives." — Annie Dillard
"You don't rise to the level of your goals. You fall to the level of your systems." — James Clear
"Every action you take is a vote for the type of person you wish to become." — James Clear
"Professionals stick to the schedule; amateurs let life get in the way." — James Clear
"Men are anxious to improve their circumstances, but are unwilling to improve themselves; they therefore remain bound." — James Allen
"Suffer the pain of discipline or suffer the pain of regret." — Jim Rohn
"Self-respect is the fruit of discipline." — Abraham Joshua Heschel
"Without self-discipline, success is impossible, period." — Lou Holtz
"Ability is what you're capable of doing. Motivation determines what you do. Attitude determines how well you do it." — Lou Holtz
"In the middle of difficulty lies opportunity." — Albert Einstein
"Genius is one percent inspiration and ninety-nine percent perspiration." — Thomas Edison
"Our greatest weakness lies in giving up. The most certain way to succeed is always to try just one more time." — Thomas Edison
"Energy and persistence conquer all things." — Benjamin Franklin
"Well done is better than well said." — Benjamin Franklin
"Diligence is the mother of good luck." — Benjamin Franklin
"By failing to prepare, you are preparing to fail." — Benjamin Franklin
"I never dreamed about success. I worked for it." — Estée Lauder
"You have to expect things of yourself before you can do them." — Michael Jordan
"Obstacles don't have to stop you. If you run into a wall, don't turn around and give up. Figure out how to climb it." — Michael Jordan
"I've failed over and over and over again in my life. And that is why I succeed." — Michael Jordan
"Some people want it to happen, some wish it would happen, others make it happen." — Michael Jordan
"Today I will do what others won't, so tomorrow I can accomplish what others can't." — Jerry Rice
"If you aren't going all the way, why go at all?" — Joe Namath
"Winning isn't everything, but wanting to win is." — Vince Lombardi
"Perfection is not attainable, but if we chase perfection we can catch excellence." — Vince Lombardi
"Make each day your masterpiece." — John Wooden
"Be more concerned with your character than your reputation." — John Wooden
"Do not let what you cannot do interfere with what you can do." — John Wooden
"Age is no barrier. It's a limitation you put on your mind." — Jackie Joyner-Kersee
"The difference between the impossible and the possible lies in a person's determination." — Tommy Lasorda
"It takes twenty years to make an overnight success." — Eddie Cantor
"Start where you are. Use what you have. Do what you can." — Arthur Ashe
"Success is the sum of small efforts, repeated day in and day out." — Robert Collier
"Action is the foundational key to all success." — Pablo Picasso
"What we fear doing most is usually what we most need to do." — Tim Ferriss
"He who conquers himself is the mightiest warrior." — Confucius
"No man is free who is not master of himself." — Epictetus
"Wealth consists not in having great possessions, but in having few wants." — Epictetus
"Waste no more time arguing about what a good man should be. Be one." — Marcus Aurelius
"The impediment to action advances action. What stands in the way becomes the way." — Marcus Aurelius
"Luck is what happens when preparation meets opportunity." — Seneca
"Difficulties strengthen the mind, as labor does the body." — Seneca
"You don't have to be extreme, just consistent." — Proverb
""".trimIndent()

    val all: List<String> = QUOTES_TEXT
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    fun random(excluding: String? = null, rng: Random = Random): String {
        val candidates = if (excluding != null && all.size > 1) {
            all.filter { it != excluding }
        } else {
            all
        }
        return candidates[rng.nextInt(candidates.size)]
    }
}
